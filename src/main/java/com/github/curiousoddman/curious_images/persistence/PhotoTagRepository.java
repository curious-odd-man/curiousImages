package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoTagRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.TagEmbeddingRecord;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.Record2;
import org.jooq.Result;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
     * Case-insensitive exact tag-name lookup for the {@code #tag} search filter.
     */
    public Optional<Long> findIdByTagName(String tag) {
        return Optional.ofNullable(
                dsl.select(TAG_EMBEDDING.ID)
                   .from(TAG_EMBEDDING)
                   .where(TAG_EMBEDDING.TAG.equalIgnoreCase(tag))
                   .fetchOne(TAG_EMBEDDING.ID));
    }

    /**
     * Case-insensitive prefix search over tag names, for the {@code #}-tag autocomplete in the
     * library search field.
     */
    public List<String> findTagSuggestions(String prefix, int limit) {
        return dsl.selectDistinct(TAG_EMBEDDING.TAG)
                  .from(TAG_EMBEDDING)
                  .join(PHOTO_TAG)
                  .on(PHOTO_TAG.TAG_ID.eq(TAG_EMBEDDING.ID))
                  .where(TAG_EMBEDDING.TAG.likeIgnoreCase(prefix + "%"))
                  .orderBy(TAG_EMBEDDING.TAG)
                  .limit(limit)
                  .fetch(TAG_EMBEDDING.TAG);
    }

    /**
     * Photo IDs carrying ALL of the given tag IDs (AND semantics — a search query like
     * {@code #beach #sunset} means both, not either). Returns an empty set for an empty
     * {@code tagIds}, rather than treating "no tags requested" as "match every photo" — that
     * distinction is {@code SearchService}'s to make, not this repository's.
     */
    public Set<Long> findPhotoIdsHavingAllTags(Collection<Long> tagIds) {
        if (tagIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(
                dsl.select(PHOTO_TAG.PHOTO_ID)
                   .from(PHOTO_TAG)
                   .where(PHOTO_TAG.TAG_ID.in(tagIds))
                   .groupBy(PHOTO_TAG.PHOTO_ID)
                   .having(DSL.countDistinct(PHOTO_TAG.TAG_ID)
                              .eq(tagIds.size()))
                   .fetch(PHOTO_TAG.PHOTO_ID));
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