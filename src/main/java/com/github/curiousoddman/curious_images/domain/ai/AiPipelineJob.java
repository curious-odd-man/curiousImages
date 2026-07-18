package com.github.curiousoddman.curious_images.domain.ai;

import com.github.curiousoddman.curious_images.dbobj.tables.records.ClipEmbeddingRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ClusterRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceEmbeddingRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.domain.index.ClipVectorIndex;
import com.github.curiousoddman.curious_images.domain.index.FaceVectorIndex;
import com.github.curiousoddman.curious_images.event.model.UserNotificationEvent;
import com.github.curiousoddman.curious_images.event.payload.FaceClipProcessingFailed;
import com.github.curiousoddman.curious_images.persistence.*;
import com.github.curiousoddman.curious_images.util.EmbeddingMath;
import com.github.curiousoddman.curious_images.util.ImageUtils;
import com.github.curiousoddman.curious_images.util.QueryBuffer;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import com.github.curiousoddman.curious_images.util.async.jobs.BackgroundJob;
import com.github.curiousoddman.curious_images.util.async.jobs.IrrecoverableIterationException;
import com.github.curiousoddman.curious_images.util.async.jobs.JobManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.opencv.core.Mat;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.github.curiousoddman.curious_images.util.QueryBuffer.DB_FLUSH_BATCH_SIZE;

@Slf4j
@RequiredArgsConstructor
public class AiPipelineJob extends BackgroundJob {
    public static final String AI_PIPELINE = "AI Pipeline";

    private static final String ARCFACE_MODEL_VER    = "arcface_r50";
    private static final String CLIP_IMAGE_MODEL_VER = "clip_vit_b32";

    private final DSLContext               dsl;
    private final PhotoRepository          photoRepo;
    private final FaceRepository           faceRepo;
    private final ClusterRepository        clusterRepo;
    private final FaceEmbeddingRepository  faceEmbeddingRepo;
    private final ClipEmbeddingRepository  clipEmbeddingRepo;
    private final RetinaFaceDetector       retinaFaceDetector;
    private final ArcFaceEncoder           arcFaceEncoder;
    private final FaceAligner              faceAligner;
    private final ClipImageEncoder         clipImageEncoder;
    private final ClipVectorIndex          clipVectorIndex;
    private final FaceVectorIndex          faceVectorIndex;
    private final PersonClusteringService  personClusteringService;
    private final TimeProvider             timeProvider;
    private final FaceThumbnailsRepository faceThumbnailsRepository;
    private final JobManager               jobManager;
    private final ImageUtils               imageUtils;
    private final boolean                  faceDetectionOnly;

    @Override
    public void runImpl() throws Exception {
        publishStarted("Starting AI pipeline...");
        try {
            runFaceAndClipEmbedding();
            if (isInterruptRequested()) {
                publishInterrupted();
                return;
            }

            if (!faceDetectionOnly) {
                runLuceneIndexing();
                if (isInterruptRequested()) {
                    publishInterrupted();
                    return;
                }
            }

            personClusteringService.clusterIncremental();

            publishEnded("AI pipeline complete");
            jobManager.submitAlbumGenerationJob();
        } catch (Exception e) {
            log.error("AI pipeline failed", e);
            publishFailed(e);
            throw e;
        }
    }

    private void runFaceAndClipEmbedding() {
        List<Long> facePending = photoRepo.findPendingFaceDetect();
        List<Long> clipPending = faceDetectionOnly ? List.of() : photoRepo.findPendingClipEmbed();

        // Union while preserving a stable, deduplicated order.
        LinkedHashSet<Long> allPending = new LinkedHashSet<>(facePending);
        allPending.addAll(clipPending);

        if (allPending.isEmpty()) {
            log.info("Face/CLIP: no pending photos");
            return;
        }

        Set<Long>  faceSet  = new HashSet<>(facePending);
        Set<Long>  clipSet  = new HashSet<>(clipPending);
        List<Long> photoIds = new ArrayList<>(allPending);

        publishInProgress("Processing photos...", 0, photoIds.size());
        try (QueryBuffer buffer = new QueryBuffer(dsl)) {
            for (int i = 0; i < photoIds.size(); i++) {
                if (detectFacesAndEmbedding(photoIds, i, faceSet, buffer, clipSet)) {
                    return;
                }
            }
        }
    }

    private boolean detectFacesAndEmbedding(List<Long> photoIds, int i, Set<Long> faceSet, QueryBuffer buffer, Set<Long> clipSet) {
        if (isInterruptRequested()) {
            return true;
        }

        long          photoId       = photoIds.get(i);
        LocalDateTime now           = timeProvider.now();
        String        lastPhotoPath = "";
        Mat           img           = null;
        PhotoRecord   photo         = null;
        try {
            photo = photoRepo.findById(photoId)
                             .orElseThrow(() -> new IllegalStateException("Photo not found: " + photoId));
            lastPhotoPath = photo.getAbsolutePath();
            img = imageUtils.imageOrCr2Preview(photo)
                            .orElseThrow();

            if (faceSet.contains(photoId)) {
                List<DetectedFace> faces = retinaFaceDetector.detect(img);
                for (DetectedFace face : faces) {
                    Path faceThumbnailPath = faceThumbnailsRepository.createFaceThumbnail(photo.getAbsolutePath(), ImageUtils.toBufferedImage(img), face);
                    long faceId = faceRepo.insertAndGetId(
                            photoId, face.x(), face.y(), face.w(), face.h(),
                            face.confidence(), toLandmarks(face.landmarks()), now, faceThumbnailPath);

                    Mat     aligned   = faceAligner.align(faceId, img, face.landmarks());
                    float[] embedding = arcFaceEncoder.encode(aligned);
                    aligned.release();
                    buffer.add(faceEmbeddingRepo.upsertQuery(faceId, embedding, ARCFACE_MODEL_VER));
                }
                buffer.add(photoRepo.markFaceDetectAndEmbedDoneQuery(photoId, now));
            }

            if (clipSet.contains(photoId)) {
                float[] clipEmbedding = clipImageEncoder.encode(img);
                buffer.add(
                        clipEmbeddingRepo.upsertQuery(photoId, clipEmbedding, CLIP_IMAGE_MODEL_VER),
                        photoRepo.markClipEmbedDoneQuery(photoId, now)
                );
            }
        } catch (IrrecoverableIterationException e) {
            log.warn("Face/CLIP processing failed for photo {}", photoId, e);
            buffer.add(photoRepo.markErrorQuery(photoId, e.getMessage(), now));
            log.error("Exiting loop - cannot recover from that....");
            publishFailed(e.getCause());
            return true;
        } catch (Exception e) {
            log.error("Face/CLIP processing failed for photo {}", photoId, e);
            eventPublisher.publishEvent(new UserNotificationEvent(this, new FaceClipProcessingFailed(photo, e)));
            buffer.add(photoRepo.markErrorQuery(photoId, e.getMessage(), now));
        } finally {
            if (img != null) {
                img.release(); // native memory — must release explicitly, GC won't do it
            }
        }

        publishProgressThrottled("Processing photos", i + 1, photoIds.size(),
                lastPhotoPath, i == photoIds.size() - 1);
        return false;
    }

    // ── Stage 4: Lucene indexing ──────────────────────────────────────────────

    private void runLuceneIndexing() throws Exception {
        List<Long> photoIds = photoRepo.findPendingLuceneIndex();
        if (photoIds.isEmpty()) {
            log.info("Lucene indexing: no pending photos");
            return;
        }
        publishInProgress("Indexing vectors...", 0, photoIds.size());

        try (QueryBuffer buffer = new QueryBuffer(dsl)) {
            for (int i = 0; i < photoIds.size(); i++) {
                if (isInterruptRequested()) {
                    clipVectorIndex.commit();
                    faceVectorIndex.commit();
                    return;
                }

                long          photoId = photoIds.get(i);
                LocalDateTime now     = timeProvider.now();
                try {
                    // Index CLIP embedding
                    ClipEmbeddingRecord clipRec = clipEmbeddingRepo.findByPhotoId(photoId)
                                                                   .orElse(null);
                    if (clipRec != null) {
                        float[] clipEmbed = EmbeddingMath.getFloats(clipRec.getEmbedding());
                        clipVectorIndex.upsert(photoId, clipEmbed);
                    }

                    // Index face embeddings
                    List<FaceRecord> faces = faceRepo.findByPhotoId(photoId);
                    List<Long> faceIds = faces.stream()
                                              .map(FaceRecord::getId)
                                              .toList();
                    Map<Long, FaceEmbeddingRecord> faceEmbeds = faceEmbeddingRepo.findByFaceIds(faceIds);
                    for (FaceRecord face : faces) {
                        FaceEmbeddingRecord emb = faceEmbeds.get(face.getId());
                        if (emb != null) {
                            float[] faceEmbed = EmbeddingMath.getFloats(emb.getEmbedding());
                            // TODO: this index is only refreshed here, at Lucene-indexing time — it is
                            //  NOT updated when a manual correction (reassign/merge/exclude, FR1-FR5)
                            //  changes a face's owner later. Revisit if face-similarity search starts
                            //  relying on this index reflecting corrections promptly.
                            Long personId = (face.getClusterId() != null)
                                    ? clusterRepo.findById(face.getClusterId())
                                                 .map(ClusterRecord::getPersonId)
                                                 .orElse(null)
                                    : null;
                            faceVectorIndex.upsert(face.getId(), personId, faceEmbed);
                        }
                    }

                    buffer.add(photoRepo.markLuceneIndexDoneQuery(photoId, now));
                } catch (Exception e) {
                    log.warn("Lucene indexing failed for photo {}", photoId, e);
                    buffer.add(photoRepo.markErrorQuery(photoId, e.getMessage(), now));
                }

                int nextIndex = i + 1;
                if (nextIndex % DB_FLUSH_BATCH_SIZE == 0) {
                    clipVectorIndex.commit();
                    faceVectorIndex.commit();
                }
                publishProgressThrottled("Indexing vectors", nextIndex, photoIds.size(),
                        "Photo id: " + photoId, i == photoIds.size() - 1);
            }
        }
        clipVectorIndex.commit();
        faceVectorIndex.commit();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Landmarks toLandmarks(float[][] landmarks) {
        return new Landmarks(
                landmarks[0][0], landmarks[0][1],
                landmarks[1][0], landmarks[1][1],
                landmarks[2][0], landmarks[2][1],
                landmarks[3][0], landmarks[3][1],
                landmarks[4][0], landmarks[4][1]
        );
    }

    @Override
    public String getProcessName() {
        return AI_PIPELINE;
    }
}