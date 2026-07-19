package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.ClipEmbeddingRecord;
import com.github.curiousoddman.curious_images.util.EmbeddingMath;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static com.github.curiousoddman.curious_images.dbobj.Tables.CLIP_EMBEDDING;

/**
 * Hand-written jOOQ repository for {@code clip_embedding}.
 * Embeddings are stored as raw little-endian float32 bytes: 512 floats × 4 bytes = 2048 bytes.
 */
@Repository
@RequiredArgsConstructor
public class ClipEmbeddingRepository {

    private final DSLContext dsl;

    /**
     * Returns an unexecuted MERGE (upsert) for a CLIP embedding row.
     *
     * @param photoId      the photo row id
     * @param embedding    512-dim float array, already L2-normalised
     * @param modelVersion e.g. {@code "clip_vit_b32"}
     */
    public Query upsertQuery(long photoId, float[] embedding, String modelVersion) {
        byte[] bytes = EmbeddingMath.toBytes(embedding);
        return dsl.mergeInto(CLIP_EMBEDDING)
                  .using(dsl.selectOne())
                  .on(CLIP_EMBEDDING.PHOTO_ID.eq(photoId))
                  .whenMatchedThenUpdate()
                  .set(CLIP_EMBEDDING.EMBEDDING, bytes)
                  .set(CLIP_EMBEDDING.MODEL_VER, modelVersion)
                  .whenNotMatchedThenInsert(CLIP_EMBEDDING.PHOTO_ID, CLIP_EMBEDDING.EMBEDDING, CLIP_EMBEDDING.MODEL_VER)
                  .values(photoId, bytes, modelVersion);
    }

    public Optional<ClipEmbeddingRecord> findByPhotoId(long photoId) {
        return Optional.ofNullable(dsl.selectFrom(CLIP_EMBEDDING)
                                      .where(CLIP_EMBEDDING.PHOTO_ID.eq(photoId))
                                      .fetchOne());
    }

    /**
     * Loads all CLIP embeddings — used by album generation (similarity clustering).
     */
    public List<ClipEmbeddingRecord> findAll() {
        return dsl.selectFrom(CLIP_EMBEDDING)
                  .fetch();
    }

    /**
     * Deletes the CLIP embedding row for a photo, if any. Call inside the same transaction as the
     * corresponding {@code PHOTO}/{@code FACE} row changes — see {@code PhotoRotationService}, the
     * only current caller (manual rotation correction wipes a photo's AI data outright).
     */
    public void deleteByPhotoId(DSLContext ctx, long photoId) {
        ctx.deleteFrom(CLIP_EMBEDDING)
           .where(CLIP_EMBEDDING.PHOTO_ID.eq(photoId))
           .execute();
    }

    public List<ClipEmbeddingRecord> findByPhotoIds(List<Long> photoIds) {
        return dsl.selectFrom(CLIP_EMBEDDING)
                  .where(CLIP_EMBEDDING.PHOTO_ID.in(photoIds))
                  .fetch();
    }
}
