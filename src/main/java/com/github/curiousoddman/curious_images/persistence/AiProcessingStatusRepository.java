package com.github.curiousoddman.curious_images.persistence;

import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

import static com.github.curiousoddman.curious_images.dbobj.Tables.AI_PROCESSING_STATUS;

/**
 * Hand-written jOOQ repository for {@code ai_processing_status}.
 * <p>
 * Every method that touches a single photo returns an unexecuted {@link Query} so
 * {@code AiPipelineJob} can buffer and batch them — same pattern as {@code PhotoRepository}.
 * The {@code findPending*} query methods execute immediately because they produce lists consumed
 * before any batching begins.
 */
@Repository
@RequiredArgsConstructor
public class AiProcessingStatusRepository {

    private final DSLContext dsl;

    /**
     * Inserts a blank status row (all flags false) for a photo that has no row yet.
     * Uses MERGE (upsert) so it is safe to call even if a row already exists from a
     * previous partial run.
     */
    public Query upsertQuery(long photoId, LocalDateTime now) {
        return dsl.mergeInto(AI_PROCESSING_STATUS)
                .using(dsl.selectOne())
                .on(AI_PROCESSING_STATUS.PHOTO_ID.eq(photoId))
                .whenNotMatchedThenInsert(
                        AI_PROCESSING_STATUS.PHOTO_ID,
                        AI_PROCESSING_STATUS.FACE_DETECT_DONE,
                        AI_PROCESSING_STATUS.FACE_EMBED_DONE,
                        AI_PROCESSING_STATUS.CLIP_EMBED_DONE,
                        AI_PROCESSING_STATUS.LUCENE_INDEX_DONE,
                        AI_PROCESSING_STATUS.RETRY_COUNT,
                        AI_PROCESSING_STATUS.UPDATED_AT)
                .values(photoId, false, false, false, false, (short) 0, now);
    }

    public Query markFaceDetectDoneQuery(long photoId, LocalDateTime now) {
        return dsl.update(AI_PROCESSING_STATUS)
                .set(AI_PROCESSING_STATUS.FACE_DETECT_DONE, true)
                .set(AI_PROCESSING_STATUS.UPDATED_AT, now)
                .where(AI_PROCESSING_STATUS.PHOTO_ID.eq(photoId));
    }

    public Query markFaceEmbedDoneQuery(long photoId, LocalDateTime now) {
        return dsl.update(AI_PROCESSING_STATUS)
                .set(AI_PROCESSING_STATUS.FACE_EMBED_DONE, true)
                .set(AI_PROCESSING_STATUS.UPDATED_AT, now)
                .where(AI_PROCESSING_STATUS.PHOTO_ID.eq(photoId));
    }

    public Query markClipEmbedDoneQuery(long photoId, LocalDateTime now) {
        return dsl.update(AI_PROCESSING_STATUS)
                .set(AI_PROCESSING_STATUS.CLIP_EMBED_DONE, true)
                .set(AI_PROCESSING_STATUS.UPDATED_AT, now)
                .where(AI_PROCESSING_STATUS.PHOTO_ID.eq(photoId));
    }

    public Query markLuceneIndexDoneQuery(long photoId, LocalDateTime now) {
        return dsl.update(AI_PROCESSING_STATUS)
                .set(AI_PROCESSING_STATUS.LUCENE_INDEX_DONE, true)
                .set(AI_PROCESSING_STATUS.UPDATED_AT, now)
                .where(AI_PROCESSING_STATUS.PHOTO_ID.eq(photoId));
    }

    public Query markErrorQuery(long photoId, String errorMessage, LocalDateTime now) {
        String truncated = errorMessage != null && errorMessage.length() > 1024
                ? errorMessage.substring(0, 1021) + "..."
                : errorMessage;
        return dsl.update(AI_PROCESSING_STATUS)
                .set(AI_PROCESSING_STATUS.LAST_ERROR, truncated)
                .set(AI_PROCESSING_STATUS.RETRY_COUNT,
                        AI_PROCESSING_STATUS.RETRY_COUNT.add((short) 1))
                .set(AI_PROCESSING_STATUS.UPDATED_AT, now)
                .where(AI_PROCESSING_STATUS.PHOTO_ID.eq(photoId));
    }

    /**
     * Returns photo IDs that still need face detection (status row exists but flag is false).
     */
    public List<Long> findPendingFaceDetect() {
        return dsl.select(AI_PROCESSING_STATUS.PHOTO_ID)
                .from(AI_PROCESSING_STATUS)
                .where(AI_PROCESSING_STATUS.FACE_DETECT_DONE.eq(false))
                .fetch(AI_PROCESSING_STATUS.PHOTO_ID);
    }

    /**
     * Returns photo IDs that still need face embedding.
     */
    public List<Long> findPendingFaceEmbed() {
        return dsl.select(AI_PROCESSING_STATUS.PHOTO_ID)
                .from(AI_PROCESSING_STATUS)
                .where(AI_PROCESSING_STATUS.FACE_DETECT_DONE.eq(true))
                .and(AI_PROCESSING_STATUS.FACE_EMBED_DONE.eq(false))
                .fetch(AI_PROCESSING_STATUS.PHOTO_ID);
    }

    /**
     * Returns photo IDs that still need CLIP embedding.
     */
    public List<Long> findPendingClipEmbed() {
        return dsl.select(AI_PROCESSING_STATUS.PHOTO_ID)
                .from(AI_PROCESSING_STATUS)
                .where(AI_PROCESSING_STATUS.CLIP_EMBED_DONE.eq(false))
                .fetch(AI_PROCESSING_STATUS.PHOTO_ID);
    }

    /**
     * Returns photo IDs that still need Lucene indexing.
     */
    public List<Long> findPendingLuceneIndex() {
        return dsl.select(AI_PROCESSING_STATUS.PHOTO_ID)
                .from(AI_PROCESSING_STATUS)
                .where(AI_PROCESSING_STATUS.CLIP_EMBED_DONE.eq(true))
                .and(AI_PROCESSING_STATUS.LUCENE_INDEX_DONE.eq(false))
                .fetch(AI_PROCESSING_STATUS.PHOTO_ID);
    }
}
