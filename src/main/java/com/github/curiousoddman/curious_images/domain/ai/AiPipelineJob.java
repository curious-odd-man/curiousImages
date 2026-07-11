package com.github.curiousoddman.curious_images.domain.ai;

import com.github.curiousoddman.curious_images.dbobj.tables.records.ClipEmbeddingRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ClusterRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceEmbeddingRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.domain.index.ClipVectorIndex;
import com.github.curiousoddman.curious_images.domain.index.FaceVectorIndex;
import com.github.curiousoddman.curious_images.persistence.*;
import com.github.curiousoddman.curious_images.util.ImageUtils;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import com.github.curiousoddman.curious_images.util.async.jobs.BackgroundJob;
import com.github.curiousoddman.curious_images.util.async.jobs.IrrecoverableIterationException;
import com.github.curiousoddman.curious_images.util.async.jobs.JobManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.impl.DSL;
import org.opencv.core.Mat;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
public class AiPipelineJob extends BackgroundJob {
    public static final String AI_PIPELINE = "AI Pipeline";

    private static final int    DB_FLUSH_BATCH_SIZE  = 50;
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

        List<Query> buffer = new ArrayList<>(DB_FLUSH_BATCH_SIZE);
        for (int i = 0; i < photoIds.size(); i++) {
            if (isInterruptRequested()) {
                flush(buffer);
                return;
            }

            long          photoId       = photoIds.get(i);
            LocalDateTime now           = timeProvider.now();
            String        lastPhotoPath = "";
            Mat           img           = null;
            try {
                PhotoRecord photo = photoRepo.findById(photoId)
                                             .orElseThrow(() -> new IllegalStateException("Photo not found: " + photoId));
                lastPhotoPath = photo.getAbsolutePath();
                img = ImageUtils.loadImageOriented(photo.getAbsolutePath(), photo.getOrientation());

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
                    buffer.add(photoRepo.markFaceDetectDoneQuery(photoId, now));
                    buffer.add(photoRepo.markFaceEmbedDoneQuery(photoId, now));
                }

                if (clipSet.contains(photoId)) {
                    float[] clipEmbedding = clipImageEncoder.encode(img);
                    buffer.add(clipEmbeddingRepo.upsertQuery(photoId, clipEmbedding, CLIP_IMAGE_MODEL_VER));
                    buffer.add(photoRepo.markClipEmbedDoneQuery(photoId, now));
                }
            } catch (IrrecoverableIterationException e) {
                log.warn("Face/CLIP processing failed for photo {}", photoId, e);
                buffer.add(photoRepo.markErrorQuery(photoId, e.getMessage(), now));
                log.error("Exiting loop - cannot recover from that....");
                publishFailed(e.getCause());
                return;
            } catch (Exception e) {
                log.error("Face/CLIP processing failed for photo {}", photoId, e);
                buffer.add(photoRepo.markErrorQuery(photoId, e.getMessage(), now));
            } finally {
                if (img != null) {
                    img.release(); // native memory — must release explicitly, GC won't do it
                }
            }

            if (buffer.size() >= DB_FLUSH_BATCH_SIZE) {
                flush(buffer);
            }
            publishProgressThrottled("Processing photos", i + 1, photoIds.size(),
                    lastPhotoPath, i == photoIds.size() - 1);
        }
        flush(buffer);
    }

    // ── Stage 4: Lucene indexing ──────────────────────────────────────────────

    private void runLuceneIndexing() throws Exception {
        List<Long> photoIds = photoRepo.findPendingLuceneIndex();
        if (photoIds.isEmpty()) {
            log.info("Lucene indexing: no pending photos");
            return;
        }
        publishInProgress("Indexing vectors...", 0, photoIds.size());

        List<Query> statusBuffer = new ArrayList<>(DB_FLUSH_BATCH_SIZE);

        for (int i = 0; i < photoIds.size(); i++) {
            if (isInterruptRequested()) {
                flush(statusBuffer);
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
                    float[] clipEmbed = ClipEmbeddingRepository.getFloats(clipRec.getEmbedding());
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
                        float[] faceEmbed = ClipEmbeddingRepository.getFloats(emb.getEmbedding());
                        // face no longer stores its person directly (see FaceRepository); resolve
                        // via cluster_id -> cluster.person_id instead.
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

                statusBuffer.add(photoRepo.markLuceneIndexDoneQuery(photoId, now));
            } catch (Exception e) {
                log.warn("Lucene indexing failed for photo {}", photoId, e);
                statusBuffer.add(photoRepo.markErrorQuery(photoId, e.getMessage(), now));
            }

            if (statusBuffer.size() >= DB_FLUSH_BATCH_SIZE) {
                flush(statusBuffer);
                clipVectorIndex.commit();
                faceVectorIndex.commit();
            }
            publishProgressThrottled("Indexing vectors", i + 1, photoIds.size(),
                    "Photo id: " + photoId, i == photoIds.size() - 1);
        }
        flush(statusBuffer);
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

    private void flush(List<Query> buffer) {
        if (buffer.isEmpty()) {
            return;
        }
        dsl.transaction(cfg -> DSL.using(cfg)
                                  .batch(buffer)
                                  .execute());
        buffer.clear();
    }

    @Override
    public String getProcessName() {
        return AI_PIPELINE;
    }
}