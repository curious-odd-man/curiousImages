package com.github.curiousoddman.curious_images.domain.ai;

import com.github.curiousoddman.curious_images.dbobj.tables.records.ClipEmbeddingRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceEmbeddingRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.domain.index.ClipVectorIndex;
import com.github.curiousoddman.curious_images.domain.index.FaceVectorIndex;
import com.github.curiousoddman.curious_images.event.model.RegenerateAlbumsEvent;
import com.github.curiousoddman.curious_images.persistence.ClipEmbeddingRepository;
import com.github.curiousoddman.curious_images.persistence.FaceEmbeddingRepository;
import com.github.curiousoddman.curious_images.persistence.FaceRepository;
import com.github.curiousoddman.curious_images.persistence.FaceThumbnailsRepository;
import com.github.curiousoddman.curious_images.persistence.Landmarks;
import com.github.curiousoddman.curious_images.persistence.PhotoRepository;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import com.github.curiousoddman.curious_images.util.async.jobs.BackgroundJob;
import com.github.curiousoddman.curious_images.util.async.jobs.IrrecoverableIterationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.impl.DSL;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.curiousoddman.curious_images.domain.common.thumbnail.ThumbnailGenerator.rotate;

@Slf4j
@RequiredArgsConstructor
public class AiPipelineJob extends BackgroundJob {
    public static final String AI_PIPELINE = "AI Pipeline";

    private static final int    DB_FLUSH_BATCH_SIZE  = 50;
    private static final String ARCFACE_MODEL_VER    = "arcface_r50";
    private static final String CLIP_IMAGE_MODEL_VER = "clip_vit_b32";

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final DSLContext               dsl;
    private final PhotoRepository          photoRepo;
    private final FaceRepository           faceRepo;
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
    private final boolean                  faceDetectionOnly;

    // ── Pipeline orchestration ────────────────────────────────────────────────

    @Override
    public void runImpl() throws Exception {
        publishStarted("Starting AI pipeline...");
        try {
            runFaceDetectionAndEmbedding();
            if (isInterruptRequested()) {
                publishInterrupted();
                return;
            }

            if (!faceDetectionOnly) {
                runClipEmbedding();
                if (isInterruptRequested()) {
                    publishInterrupted();
                    return;
                }

                runLuceneIndexing();
                if (isInterruptRequested()) {
                    publishInterrupted();
                    return;
                }
            }

            personClusteringService.cluster();

            publishEnded("AI pipeline complete");
            eventPublisher.publishEvent(new RegenerateAlbumsEvent(this));
        } catch (Exception e) {
            log.error("AI pipeline failed", e);
            publishFailed(e);
            throw e;
        }
    }

    private void runFaceDetectionAndEmbedding() {
        List<Long> photoIds = photoRepo.findPendingFaceDetect();
        if (photoIds.isEmpty()) {
            log.info("Face detection: no pending photos");
            return;
        }
        publishInProgress("Detecting faces...", 0, photoIds.size());

        List<Query> buffer = new ArrayList<>(DB_FLUSH_BATCH_SIZE);
        for (int i = 0; i < photoIds.size(); i++) {
            if (isInterruptRequested()) {
                flush(buffer);
                return;
            }

            long          photoId       = photoIds.get(i);
            LocalDateTime now           = timeProvider.now();
            String        lastPhotoPath = "";
            try {
                PhotoRecord photo = photoRepo.findById(photoId)
                                             .orElseThrow(() -> new IllegalStateException("Photo not found: " + photoId));
                lastPhotoPath = photo.getAbsolutePath();
                BufferedImage      img   = loadImageOriented(photo.getAbsolutePath(), photo.getOrientation());
                List<DetectedFace> faces = retinaFaceDetector.detect(img);

                for (DetectedFace face : faces) {
                    Path faceThumbnailPath = faceThumbnailsRepository.createFaceThumbnail(img, face);
                    long faceId = faceRepo.insertAndGetId(
                            photoId, face.x(), face.y(), face.w(), face.h(),
                            face.confidence(), toLandmarks(face.landmarks()), now, faceThumbnailPath);

                    BufferedImage aligned   = faceAligner.align(faceId, img, face.landmarks());
                    float[]       embedding = arcFaceEncoder.encode(aligned);
                    buffer.add(faceEmbeddingRepo.upsertQuery(faceId, embedding, ARCFACE_MODEL_VER));
                }
                buffer.add(photoRepo.markFaceDetectDoneQuery(photoId, now));
                buffer.add(photoRepo.markFaceEmbedDoneQuery(photoId, now));
            } catch (IrrecoverableIterationException e) {
                log.warn("Face detection/embedding failed for photo {}", photoId, e);
                buffer.add(photoRepo.markErrorQuery(photoId, e.getMessage(), now));
                log.error("Exiting loop - cannot recover from that....");
                publishFailed(e.getCause());
                return;
            } catch (Exception e) {
                log.error("Face detection/embedding failed for photo {}", photoId, e);
                buffer.add(photoRepo.markErrorQuery(photoId, e.getMessage(), now));
            }

            if (buffer.size() >= DB_FLUSH_BATCH_SIZE) {
                flush(buffer);
            }
            publishProgress("Detecting & embedding faces", i + 1, photoIds.size(),
                    lastPhotoPath, i == photoIds.size() - 1);
        }
        flush(buffer);
    }

    // ── Stage 3: CLIP image embedding ────────────────────────────────────────

    private void runClipEmbedding() {
        List<Long> photoIds = photoRepo.findPendingClipEmbed();
        if (photoIds.isEmpty()) {
            log.info("CLIP embedding: no pending photos");
            return;
        }
        publishInProgress("Generating CLIP embeddings...", 0, photoIds.size());

        List<Query> buffer = new ArrayList<>(DB_FLUSH_BATCH_SIZE);
        for (int i = 0; i < photoIds.size(); i++) {
            if (isInterruptRequested()) {
                flush(buffer);
                return;
            }

            long          photoId       = photoIds.get(i);
            LocalDateTime now           = timeProvider.now();
            String        lastPhotoPath = "";
            try {
                PhotoRecord photo = photoRepo.findById(photoId)
                                             .orElseThrow(() -> new IllegalStateException("Photo not found: " + photoId));
                lastPhotoPath = photo.getAbsolutePath();
                BufferedImage img       = loadImageOriented(photo.getAbsolutePath(), photo.getOrientation());
                float[]       embedding = clipImageEncoder.encode(img);
                buffer.add(clipEmbeddingRepo.upsertQuery(photoId, embedding, CLIP_IMAGE_MODEL_VER));
                buffer.add(photoRepo.markClipEmbedDoneQuery(photoId, now));
            } catch (IrrecoverableIterationException e) {
                log.warn("CLIP embedding failed for photo {}", photoId, e);
                buffer.add(photoRepo.markErrorQuery(photoId, e.getMessage(), now));
                log.error("Exiting loop - cannot recover from that....");
                publishFailed(e.getCause());
                return;
            } catch (Exception e) {
                log.warn("CLIP embedding failed for photo {}", photoId, e);
                buffer.add(photoRepo.markErrorQuery(photoId, e.getMessage(), now));
            }

            if (buffer.size() >= DB_FLUSH_BATCH_SIZE) {
                flush(buffer);
            }
            publishProgress("Generating CLIP embeddings", i + 1, photoIds.size(),
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
                        faceVectorIndex.upsert(face.getId(), face.getPersonId(), faceEmbed);
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
            publishProgress("Indexing vectors", i + 1, photoIds.size(),
                    "Photo id: " + photoId, i == photoIds.size() - 1);
        }
        flush(statusBuffer);
        clipVectorIndex.commit();
        faceVectorIndex.commit();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Note: This is slow! Try to reuse, when possible
     * @param absolutePath
     * @param orientation
     * @return
     * @throws IOException
     */
    public static BufferedImage loadImageOriented(String absolutePath, Integer orientation) throws IOException {    // FIXME: This is very slow. It takes >36% of time during face detection
        BufferedImage img = ImageIO.read(new File(absolutePath));
        if (img == null) {  // FIXME: also probably I need to fix images rotation on disc - or improve rotation algorithm
            throw new IOException("ImageIO could not decode: " + absolutePath);
        }
        return rotate(img, orientation);
    }

    /**
     * Serialises 5×2 landmark pixel coordinates to a compact JSON array.
     * 0 = left eye
     * 1 = right eye
     * 2 = nose
     * 3 = left mouth corner
     * 4 = right mouth corner
     */
    private Landmarks toLandmarks(float[][] landmarks) {
        return new Landmarks(
                landmarks[0][0],
                landmarks[0][1],
                landmarks[1][0],
                landmarks[1][1],
                landmarks[2][0],
                landmarks[2][1],
                landmarks[3][0],
                landmarks[3][1],
                landmarks[4][0],
                landmarks[4][1]
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
