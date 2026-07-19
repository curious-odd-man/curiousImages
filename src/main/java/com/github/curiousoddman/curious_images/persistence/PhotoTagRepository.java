package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoTagRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.TagEmbeddingRecord;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.Record2;
import org.jooq.Result;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.curiousoddman.curious_images.dbobj.Tables.PHOTO_TAG;
import static com.github.curiousoddman.curious_images.dbobj.Tables.TAG_EMBEDDING;

@Repository
@RequiredArgsConstructor
public class PhotoTagRepository {

    private final DSLContext dsl;

    /**
     * Returns every known tag, regardless of whether it currently has an embedding.
     */
    public List<TagEmbeddingRecord> findAllTags() {
        return dsl.selectFrom(TAG_EMBEDDING)
                  .fetch();
    }

    /**
     * Persists embedding/model_ver changes for the given tags.
     */
    public Query[] update(List<TagEmbeddingRecord> tags) {
        return tags.stream()
                   .map(t -> dsl.update(TAG_EMBEDDING)
                                .set(TAG_EMBEDDING.EMBEDDING, t.getEmbedding())
                                .set(TAG_EMBEDDING.MODEL_VER, t.getModelVer())
                                .where(TAG_EMBEDDING.ID.eq(t.getId())))
                   .toArray(Query[]::new);
    }

    /**
     * Links {@code tag} to {@code photoId} as an AI-assigned tag. Re-running is
     * idempotent - an existing (tag_id, photo_id) pair is left as AI-sourced.
     */
    public Query upsert(Long photoId, TagEmbeddingRecord tag, double score) {
        return dsl.insertInto(PHOTO_TAG, PHOTO_TAG.TAG_ID, PHOTO_TAG.PHOTO_ID, PHOTO_TAG.TAG_SOURCE, PHOTO_TAG.CONFIDENCE)
                  .values(tag.getId(), photoId, "AI", score)
                  .onConflict(PHOTO_TAG.TAG_ID, PHOTO_TAG.PHOTO_ID)
                  .doUpdate()
                  .set(PHOTO_TAG.TAG_SOURCE, "AI");
    }

    public Map<Long, Map<PhotoTagRecord, TagEmbeddingRecord>> findForPhotos(Collection<Long> photoIds) {
        Result<Record2<PhotoTagRecord, TagEmbeddingRecord>> result = dsl.select(PHOTO_TAG, TAG_EMBEDDING)
                                                                        .from(PHOTO_TAG)
                                                                        .join(TAG_EMBEDDING)
                                                                        .on(PHOTO_TAG.TAG_ID.eq(TAG_EMBEDDING.ID))
                                                                        .where(PHOTO_TAG.PHOTO_ID.in(photoIds))
                                                                        .fetch();
        Map<Long, Map<PhotoTagRecord, TagEmbeddingRecord>> map = new HashMap<>();
        for (Record2<PhotoTagRecord, TagEmbeddingRecord> row : result) {
            PhotoTagRecord     photoTagRecord     = row.value1();
            TagEmbeddingRecord tagEmbeddingRecord = row.value2();
            map.computeIfAbsent(photoTagRecord.getPhotoId(), _ -> new HashMap<>())
               .put(photoTagRecord, tagEmbeddingRecord);
        }

        return map;
    }
}