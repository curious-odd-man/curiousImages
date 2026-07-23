package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaTagRecord;
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

import static com.github.curiousoddman.curious_images.dbobj.Tables.MEDIA_TAG;
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
                  .join(MEDIA_TAG)
                  .on(MEDIA_TAG.TAG_ID.eq(TAG_EMBEDDING.ID))
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
                dsl.select(MEDIA_TAG.MEDIA_ID)
                   .from(MEDIA_TAG)
                   .where(MEDIA_TAG.TAG_ID.in(tagIds))
                   .groupBy(MEDIA_TAG.MEDIA_ID)
                   .having(DSL.countDistinct(MEDIA_TAG.TAG_ID)
                              .eq(tagIds.size()))
                   .fetch(MEDIA_TAG.MEDIA_ID));
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
        return dsl.insertInto(MEDIA_TAG, MEDIA_TAG.TAG_ID, MEDIA_TAG.MEDIA_ID, MEDIA_TAG.TAG_SOURCE, MEDIA_TAG.CONFIDENCE)
                  .values(tag.getId(), photoId, "AI", score)
                  .onConflict(MEDIA_TAG.TAG_ID, MEDIA_TAG.MEDIA_ID)
                  .doUpdate()
                  .set(MEDIA_TAG.TAG_SOURCE, "AI");
    }

    public Map<Long, Map<MediaTagRecord, TagEmbeddingRecord>> findForPhotos(Collection<Long> photoIds) {
        Result<Record2<MediaTagRecord, TagEmbeddingRecord>> result = dsl.select(MEDIA_TAG, TAG_EMBEDDING)
                                                                        .from(MEDIA_TAG)
                                                                        .join(TAG_EMBEDDING)
                                                                        .on(MEDIA_TAG.TAG_ID.eq(TAG_EMBEDDING.ID))
                                                                        .where(MEDIA_TAG.MEDIA_ID.in(photoIds))
                                                                        .fetch();
        Map<Long, Map<MediaTagRecord, TagEmbeddingRecord>> map = new HashMap<>();
        for (Record2<MediaTagRecord, TagEmbeddingRecord> row : result) {
            MediaTagRecord     MediaTagRecord     = row.value1();
            TagEmbeddingRecord tagEmbeddingRecord = row.value2();
            map.computeIfAbsent(MediaTagRecord.getMediaId(), _ -> new HashMap<>())
               .put(MediaTagRecord, tagEmbeddingRecord);
        }

        return map;
    }
}