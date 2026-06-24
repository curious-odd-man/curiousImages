package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceEmbeddingRecord;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.github.curiousoddman.curious_images.dbobj.Tables.FACE_EMBEDDING;

/**
 * Hand-written jOOQ repository for {@code face_embedding}.
 * <p>
 * Embeddings are stored as raw little-endian float32 bytes: 512 floats × 4 bytes = 2048 bytes.
 */
@Repository
@RequiredArgsConstructor
public class FaceEmbeddingRepository {

    private final DSLContext dsl;

    /**
     * Returns an unexecuted MERGE (upsert) for a face embedding row. Queue for batching.
     *
     * @param faceId       the face row id
     * @param embedding    512-dim float array, already L2-normalised
     * @param modelVersion e.g. {@code "arcface_r50"}
     */
    public Query upsertQuery(long faceId, float[] embedding, String modelVersion) {
        byte[] bytes = toBytes(embedding);
        return dsl.mergeInto(FACE_EMBEDDING)
                  .using(dsl.selectOne())
                  .on(FACE_EMBEDDING.FACE_ID.eq(faceId))
                  .whenMatchedThenUpdate()
                  .set(FACE_EMBEDDING.EMBEDDING, bytes)
                  .set(FACE_EMBEDDING.MODEL_VER, modelVersion)
                  .whenNotMatchedThenInsert(
                          FACE_EMBEDDING.FACE_ID,
                          FACE_EMBEDDING.EMBEDDING,
                          FACE_EMBEDDING.MODEL_VER)
                  .values(faceId, bytes, modelVersion);
    }

    /**
     * Loads all face embeddings — used by {@code PersonClusteringService}.
     */
    public List<FaceEmbeddingRecord> findAll() {
        return dsl.selectFrom(FACE_EMBEDDING)
                  .fetch();
    }

    /**
     * Loads embeddings for a set of face IDs — used during Lucene indexing.
     */
    public Map<Long, FaceEmbeddingRecord> findByFaceIds(Collection<Long> faceIds) {
        return dsl.selectFrom(FACE_EMBEDDING)
                  .where(FACE_EMBEDDING.FACE_ID.in(faceIds))
                  .fetch()
                  .stream()
                  .collect(Collectors.toMap(FaceEmbeddingRecord::getFaceId, r -> r));
    }

    // ── Serialisation helpers ─────────────────────────────────────────────────

    /**
     * Converts a float[512] embedding to 2048 little-endian bytes.
     */
    public static byte[] toBytes(float[] embedding) {
        ByteBuffer buf = ByteBuffer.allocate(embedding.length * 4)
                                   .order(ByteOrder.LITTLE_ENDIAN);
        for (float v : embedding) buf.putFloat(v);
        return buf.array();
    }

    /**
     * Converts 2048 little-endian bytes back to float[512].
     */
    public static float[] toFloats(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes)
                                   .order(ByteOrder.LITTLE_ENDIAN);
        float[]    out = new float[bytes.length / 4];
        for (int i = 0; i < out.length; i++) out[i] = buf.getFloat();
        return out;
    }
}
