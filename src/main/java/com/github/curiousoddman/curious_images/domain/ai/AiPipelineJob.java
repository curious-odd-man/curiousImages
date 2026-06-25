package com.github.curiousoddman.curious_images.domain.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ClipEmbeddingRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceEmbeddingRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.domain.index.ClipVectorIndex;
import com.github.curiousoddman.curious_images.domain.index.FaceVectorIndex;
import com.github.curiousoddman.curious_images.event.RegenerateAlbumsEvent;
import com.github.curiousoddman.curious_images.event.RunAiPipelineEvent;
import com.github.curiousoddman.curious_images.persistence.AiProcessingStatusRepository;
import com.github.curiousoddman.curious_images.persistence.ClipEmbeddingRepository;
import com.github.curiousoddman.curious_images.persistence.FaceEmbeddingRepository;
import com.github.curiousoddman.curious_images.persistence.FaceRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoRepository;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import com.github.curiousoddman.curious_images.util.async.AbstractBackgroundJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.impl.DSL;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.curiousoddman.curious_images.domain.common.thumbnail.ThumbnailGenerator.rotate;

/**
 * Orchestrates the full AI processing pipeline for newly imported photos:
 * <ol>
 *   <li>Face detection (RetinaFace)</li>
 *   <li>Face embedding (ArcFace)</li>
 *   <li>CLIP image embedding</li>
 *   <li>Lucene vector indexing</li>
 *   <li>Person clustering</li>
 * </ol>
 * Each stage queries {@link AiProcessingStatusRepository} for photos not yet at that stage,
 * so the job is fully resumable: restarting after a crash picks up exactly where it left off.
 * <p>
 * Triggered by {@link RunAiPipelineEvent}, published by {@code ImportService} after a
 * successful import. A single-flight guard (from {@link AbstractBackgroundJob#tryStart()})
 * prevents concurrent runs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiPipelineJob extends AbstractBackgroundJob {

    public static final String AI_PIPELINE = "AI Pipeline";

    private static final int    DB_FLUSH_BATCH_SIZE  = 50;
    private static final String ARCFACE_MODEL_VER    = "arcface_r50";
    private static final String CLIP_IMAGE_MODEL_VER = "clip_vit_b32";

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final DSLContext                   dsl;
    private final PhotoRepository              photoRepo;
    private final FaceRepository               faceRepo;
    private final FaceEmbeddingRepository      faceEmbeddingRepo;
    private final ClipEmbeddingRepository      clipEmbeddingRepo;
    private final AiProcessingStatusRepository aiStatusRepo;
    private final RetinaFaceDetector           retinaFaceDetector;
    private final ArcFaceEncoder               arcFaceEncoder;
    private final FaceAligner                  faceAligner;
    private final ClipImageEncoder             clipImageEncoder;
    private final ClipVectorIndex              clipVectorIndex;
    private final FaceVectorIndex              faceVectorIndex;
    private final PersonClusteringService      personClusteringService;
    private final TimeProvider                 timeProvider;
    private final ApplicationEventPublisher    applicationEventPublisher;
    private final ObjectMapper                 objectMapper;

    // ── Event listener ────────────────────────────────────────────────────────

    @EventListener
    public void onRunAiPipeline(RunAiPipelineEvent event) {
        if (!tryStart()) {
            log.warn("AI pipeline already running, ignoring new RunAiPipelineEvent");
            return;
        }
        Thread t = new Thread(this::run, "ai-pipeline");
        t.setDaemon(true);
        t.start();
    }

    // ── Pipeline orchestration ────────────────────────────────────────────────

    private void run() {
        publishStarted("Starting AI pipeline...");
        try {
            runFaceDetection();
            if (isInterruptRequested()) {
                publishInterrupted("Interrupted");
                return;
            }

            runFaceEmbedding();
            if (isInterruptRequested()) {
                publishInterrupted("Interrupted");
                return;
            }

            runClipEmbedding();
            if (isInterruptRequested()) {
                publishInterrupted("Interrupted");
                return;
            }

            runLuceneIndexing();
            if (isInterruptRequested()) {
                publishInterrupted("Interrupted");
                return;
            }

            personClusteringService.cluster();

            publishEnded("AI pipeline complete");
            applicationEventPublisher.publishEvent(new RegenerateAlbumsEvent(this));

        } catch (Exception e) {
            log.error("AI pipeline failed", e);
            publishFailed(e);
        } finally {
            finish();
        }
    }

    // ── Stage 1: Face detection ───────────────────────────────────────────────

    private void runFaceDetection() {
        List<Long> photoIds = aiStatusRepo.findPendingFaceDetect();
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

            long          photoId = photoIds.get(i);
            LocalDateTime now     = timeProvider.now();
            try {
                PhotoRecord photo = photoRepo.findById(photoId)
                                             .orElseThrow(() -> new IllegalStateException("Photo not found: " + photoId));
                BufferedImage      img   = loadImageOriented(photo.getAbsolutePath(), photo.getOrientation());
                List<DetectedFace> faces = retinaFaceDetector.detect(img);

                for (DetectedFace face : faces) {
                    buffer.add(faceRepo.insertQuery(
                            photoId, face.x(), face.y(), face.w(), face.h(),
                            face.confidence(), toLandmarkJson(face.landmarks()), now));
                }
                buffer.add(aiStatusRepo.markFaceDetectDoneQuery(photoId, now));

            } catch (Exception e) {
                log.warn("Face detection failed for photo {}", photoId, e);
                buffer.add(aiStatusRepo.markErrorQuery(photoId, e.getMessage(), now));
            }

            if (buffer.size() >= DB_FLUSH_BATCH_SIZE) {
                flush(buffer);
            }
            publishProgress("Detecting faces...", i + 1, photoIds.size(),
                    String.valueOf(photoId), i == photoIds.size() - 1);
        }
        flush(buffer);
    }

    // ── Stage 2: Face embedding (ArcFace) ────────────────────────────────────

    private void runFaceEmbedding() {
        List<Long> photoIds = aiStatusRepo.findPendingFaceEmbed();
        if (photoIds.isEmpty()) {
            log.info("Face embedding: no pending photos");
            return;
        }
        publishInProgress("Generating face embeddings...", 0, photoIds.size());

        List<Query> buffer = new ArrayList<>(DB_FLUSH_BATCH_SIZE);
        for (int i = 0; i < photoIds.size(); i++) {
            if (isInterruptRequested()) {
                flush(buffer);
                return;
            }

            long          photoId = photoIds.get(i);
            LocalDateTime now     = timeProvider.now();
            try {
                PhotoRecord photo = photoRepo.findById(photoId)
                                             .orElseThrow(() -> new IllegalStateException("Photo not found: " + photoId));
                BufferedImage    img   = loadImageOriented(photo.getAbsolutePath(), photo.getOrientation());
                List<FaceRecord> faces = faceRepo.findByPhotoId(photoId);

                for (FaceRecord face : faces) {
                    float[][] landmarks = parseLandmarks(face.getLandmarkJson());
                    BufferedImage aligned = faceAligner.align(face.getId(), img, landmarks);
/*                    Files.createDirectories(Path.of("debug"));
                    ImageIO.write(
                            aligned,
                            "jpg",
                            new File("debug/" + face.getId() + ".jpg")
                    );*/

                    float[] embedding = arcFaceEncoder.encode(aligned);
                    buffer.add(faceEmbeddingRepo.upsertQuery(face.getId(), embedding, ARCFACE_MODEL_VER));
                }
                buffer.add(aiStatusRepo.markFaceEmbedDoneQuery(photoId, now));

            } catch (Exception e) {
                log.warn("Face embedding failed for photo {}", photoId, e);
                buffer.add(aiStatusRepo.markErrorQuery(photoId, e.getMessage(), now));
            }

            if (buffer.size() >= DB_FLUSH_BATCH_SIZE) {
                flush(buffer);
            }
            publishProgress("Generating face embeddings...", i + 1, photoIds.size(),
                    String.valueOf(photoId), i == photoIds.size() - 1);
        }
        flush(buffer);
    }

    // ── Stage 3: CLIP image embedding ────────────────────────────────────────

    private void runClipEmbedding() {
        List<Long> photoIds = aiStatusRepo.findPendingClipEmbed();
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

            long          photoId = photoIds.get(i);
            LocalDateTime now     = timeProvider.now();
            try {
                PhotoRecord photo = photoRepo.findById(photoId)
                                             .orElseThrow(() -> new IllegalStateException("Photo not found: " + photoId));
                BufferedImage img       = loadImageOriented(photo.getAbsolutePath(), photo.getOrientation());
                float[]       embedding = clipImageEncoder.encode(img);
                buffer.add(clipEmbeddingRepo.upsertQuery(photoId, embedding, CLIP_IMAGE_MODEL_VER));
                buffer.add(aiStatusRepo.markClipEmbedDoneQuery(photoId, now));

            } catch (Exception e) {
                log.warn("CLIP embedding failed for photo {}", photoId, e);
                buffer.add(aiStatusRepo.markErrorQuery(photoId, e.getMessage(), now));
            }

            if (buffer.size() >= DB_FLUSH_BATCH_SIZE) {
                flush(buffer);
            }
            publishProgress("Generating CLIP embeddings...", i + 1, photoIds.size(),
                    String.valueOf(photoId), i == photoIds.size() - 1);
        }
        flush(buffer);
    }

    // ── Stage 4: Lucene indexing ──────────────────────────────────────────────

    private void runLuceneIndexing() throws Exception {
        List<Long> photoIds = aiStatusRepo.findPendingLuceneIndex();
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

                statusBuffer.add(aiStatusRepo.markLuceneIndexDoneQuery(photoId, now));

            } catch (Exception e) {
                log.warn("Lucene indexing failed for photo {}", photoId, e);
                statusBuffer.add(aiStatusRepo.markErrorQuery(photoId, e.getMessage(), now));
            }

            if (statusBuffer.size() >= DB_FLUSH_BATCH_SIZE) {
                flush(statusBuffer);
                clipVectorIndex.commit();
                faceVectorIndex.commit();
            }
            publishProgress("Indexing vectors...", i + 1, photoIds.size(),
                    String.valueOf(photoId), i == photoIds.size() - 1);
        }
        flush(statusBuffer);
        clipVectorIndex.commit();
        faceVectorIndex.commit();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public static BufferedImage loadImageOriented(String absolutePath, Integer orientation) throws IOException {
        BufferedImage img = ImageIO.read(new File(absolutePath));
        if (img == null) {
            throw new IOException("ImageIO could not decode: " + absolutePath);
        }
        return rotate(img, orientation);
    }

    /**
     * Serialises 5×2 landmark pixel coordinates to a compact JSON array.
     */
    private String toLandmarkJson(float[][] landmarks) {
        try {
            return objectMapper.writeValueAsString(landmarks);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialise landmarks", e);
        }
    }

    /**
     * Parses the stored normalised landmark JSON and converts back to pixel coordinates
     * in the original image space.
     */
    private float[][] parseLandmarks(String json) {
        if (json == null) {
            return new float[5][2];
        }
        try {
            return objectMapper.readValue(json, float[][].class);
        } catch (Exception e) {
            log.warn("Failed to parse landmark JSON, using zero landmarks", e);
            return new float[5][2];
        }
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
    protected ApplicationEventPublisher eventPublisher() {
        return applicationEventPublisher;
    }

    @Override
    protected String getProcessName() {
        return AI_PIPELINE;
    }
}
