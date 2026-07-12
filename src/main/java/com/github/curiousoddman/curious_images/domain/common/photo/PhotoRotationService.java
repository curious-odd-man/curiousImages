package com.github.curiousoddman.curious_images.domain.common.photo;

import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.domain.ai.PersonCorrectionService;
import com.github.curiousoddman.curious_images.domain.index.ClipVectorIndex;
import com.github.curiousoddman.curious_images.domain.index.FaceVectorIndex;
import com.github.curiousoddman.curious_images.persistence.ClipEmbeddingRepository;
import com.github.curiousoddman.curious_images.persistence.FaceEmbeddingRepository;
import com.github.curiousoddman.curious_images.persistence.FaceRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoRepository;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import com.github.curiousoddman.curious_images.util.async.jobs.JobManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Backs the photo grid's right-click "Rotate" actions (see {@code PhotoCellController},
 * {@code DuplicatesController}, {@code SlideshowController}).
 * <p>
 * A photo whose rotation is being manually corrected was, by definition, scanned by the AI
 * pipeline with the wrong orientation — its face bounding boxes, face crops, face/CLIP embeddings,
 * and any resulting cluster membership are all now meaningless. Rather than leave stale AI data
 * around waiting to be silently overwritten by a future pipeline run, {@link #rotate} does all of
 * the following for one photo:
 * <ol>
 *   <li>updates {@code PHOTO.ORIENTATION} to the new (normalized 0/90/180/270) value;</li>
 *   <li>resets every {@code AI_*_DONE} flag (+ clears the error/retry count) so the photo is
 *       picked up by the next {@code AiPipelineJob} run as if newly imported;</li>
 *   <li>hard-deletes the photo's existing {@code FACE} / {@code FACE_EMBEDDING} /
 *       {@code CLIP_EMBEDDING} rows, their on-disk face-thumbnail files, and their Lucene index
 *       entries — recomputing or deleting any cluster those faces belonged to via
 *       {@link PersonCorrectionService#removeFacesFromClusters}.</li>
 * </ol>
 * On success this also queues an immediate thumbnail regeneration and a fresh AI pipeline run.
 * <p>
 * <b>Transaction note:</b> cluster cleanup (step 3's clustering side) and the face/embedding row
 * deletion happen as two separate transactions, because the cluster cleanup needs the FACE rows'
 * {@code cluster_id} values to still be readable. A crash between the two could leave a photo's
 * faces detached from a recomputed/deleted cluster but not yet deleted themselves — recoverable
 * manually (the next full AI pipeline / cluster rebuild pass will not re-use them, since they're
 * about to be deleted here right after), but not fully atomic. See class discussion if stronger
 * atomicity is ever needed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoRotationService {

    /**
     * The three rotation deltas the context menu offers ("Rotate 90° CW / 90° CCW / 180°" — see
     * {@code PhotoCellController}). {@link #rotate} normalizes the resulting angle into
     * {0, 90, 180, 270} regardless of which delta is passed.
     */
    public static final int ROTATE_CW  = 90;
    public static final int ROTATE_CCW = -90;
    public static final int ROTATE_180 = 180;

    private final DSLContext              dsl;
    private final PhotoRepository         photoRepo;
    private final FaceRepository          faceRepo;
    private final FaceEmbeddingRepository faceEmbeddingRepo;
    private final ClipEmbeddingRepository clipEmbeddingRepo;
    private final PersonCorrectionService personCorrectionService;
    private final FaceVectorIndex         faceVectorIndex;
    private final ClipVectorIndex         clipVectorIndex;
    private final JobManager              jobManager;
    private final TimeProvider            timeProvider;

    /**
     * @param photoId      the photo being rotated
     * @param deltaDegrees one of {@link #ROTATE_CW}, {@link #ROTATE_CCW}, {@link #ROTATE_180} —
     *                     any value is accepted and normalized, but the menu only ever offers
     *                     these three
     */
    // FIXME: REname
    public void rotate(long photoId, int deltaDegrees) {
        PhotoRecord photo = photoRepo.findById(photoId)
                                     .orElse(null);
        if (photo == null) {
            log.warn("rotate: photo {} no longer exists", photoId);
            return;
        }

        int current    = Objects.requireNonNullElse(photo.getOrientation(), 0);
        int normalized = ((current + deltaDegrees) % 360 + 360) % 360;

        List<FaceRecord> faces = faceRepo.findByPhotoId(photoId);
        List<Long> faceIds = faces.stream()
                                  .map(FaceRecord::getId)
                                  .toList();

        // Untangle cluster membership *before* the FACE rows disappear — removeFacesFromClusters
        // needs each face's current cluster_id to know which cluster(s) to recompute/delete.
        Set<Long> orphanedPersons = personCorrectionService.removeFacesFromClusters(faceIds);
        if (!orphanedPersons.isEmpty()) {
            log.info("rotate: photo {} rotation left person(s) {} owning zero clusters — " +
                    "leaving them for manual cleanup via the People tab", photoId, orphanedPersons);
        }

        LocalDateTime now = timeProvider.now();
        dsl.transaction(cfg -> {
            DSLContext ctx = cfg.dsl();
            photoRepo.updateOrientationAndResetAi(ctx, photoId, normalized, now);
            faceEmbeddingRepo.deleteByFaceIds(ctx, faceIds);
            faceRepo.deleteByPhotoId(ctx, photoId);
            clipEmbeddingRepo.deleteByPhotoId(ctx, photoId);
        });

        deleteFaceThumbnailFiles(faces);
        removeFromLuceneIndexes(photoId, faceIds);

        // Immediate thumbnail regen (the baked-in rotation changed) + a fresh AI pass for this
        // photo, now that its AI_*_DONE flags are all false again.
        jobManager.submitThumbnailGenerationJob(List.of(photoId));
        jobManager.submitAiPipelineJob();
    }

    private void deleteFaceThumbnailFiles(List<FaceRecord> faces) {
        for (FaceRecord face : faces) {
            String path = face.getThumbnailAbsolutePath();
            if (path == null) {
                continue;
            }
            try {
                if (!new File(path).delete()) {
                    log.debug("rotate: face thumbnail {} was already gone", path);
                }
            } catch (Exception e) {
                log.warn("rotate: failed to delete face thumbnail {}", path, e);
            }
        }
    }

    private void removeFromLuceneIndexes(long photoId, List<Long> faceIds) {
        try {
            for (Long faceId : faceIds) {
                faceVectorIndex.delete(faceId);
            }
            clipVectorIndex.delete(photoId);
            faceVectorIndex.commit();
            clipVectorIndex.commit();
        } catch (Exception e) {
            // Non-fatal: DB state (the real source of truth) is already committed at this point
            // regardless. Worst case is a harmless dangling Lucene entry for a deleted face/photo
            // id until the indexes are next rebuilt — those ids are never reused, so it can never
            // resurface as a false match against a *different* real face/photo.
            log.warn("rotate: failed to remove photo {} / faces {} from Lucene indexes", photoId, faceIds, e);
        }
    }
}
