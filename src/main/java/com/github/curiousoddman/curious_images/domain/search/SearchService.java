package com.github.curiousoddman.curious_images.domain.search;

import com.github.curiousoddman.curious_images.dbobj.tables.records.ClipEmbeddingRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceRecord;
import com.github.curiousoddman.curious_images.domain.ai.ClipTextEncoder;
import com.github.curiousoddman.curious_images.domain.index.ClipVectorIndex;
import com.github.curiousoddman.curious_images.persistence.ClipEmbeddingRepository;
import com.github.curiousoddman.curious_images.persistence.FaceRepository;
import com.github.curiousoddman.curious_images.persistence.PersonRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoTagRepository;
import com.github.curiousoddman.curious_images.util.EmbeddingMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Exposes semantic text-to-image search and similar-media discovery backed by the
 * Lucene CLIP vector index.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchService {

    private final ClipTextEncoder         clipTextEncoder;
    private final ClipVectorIndex         clipVectorIndex;
    private final ClipEmbeddingRepository clipEmbeddingRepo;
    private final FaceRepository          faceRepo;
    private final PersonRepository        personRepo;
    private final PhotoTagRepository      photoTagRepo;

    /**
     * Encodes {@code query} with the CLIP text encoder and returns up to {@code topK} media
     * IDs ordered by descending cosine similarity.
     */
    public List<Long> semanticSearch(String query, int topK) throws Exception {
        float[] textEmbedding = clipTextEncoder.encode(query);
        return clipVectorIndex.search(textEmbedding, topK);
    }

    /**
     * Returns up to {@code topK} media IDs visually similar to {@code photoId}, excluding
     * the query media itself.
     */
    public List<Long> similarPhotos(long photoId, int topK) throws Exception {
        ClipEmbeddingRecord rec = clipEmbeddingRepo.findByMediaId(photoId)
                                                   .orElseThrow(() -> new IllegalStateException(
                                                           "No CLIP embedding for media " + photoId +
                                                                   " — run the AI pipeline first."));
        float[]    embedding = EmbeddingMath.getFloats(rec.getEmbedding());
        List<Long> results   = clipVectorIndex.search(embedding, topK + 1);
        results = results.stream()
                         .filter(id -> id != photoId)
                         .limit(topK)
                         .collect(Collectors.toList());
        return results;
    }

    /**
     * Parses {@code rawQuery} for {@code @person} / {@code #tag} filters plus free text (see
     * {@link SearchQueryParser}) and delegates to {@link #search(ParsedSearchQuery, int)}.
     */
    public List<Long> search(String rawQuery, int topK) throws Exception {
        return search(SearchQueryParser.parse(rawQuery), topK);
    }

    /**
     * Combined {@code @person} + {@code #tag} + semantic search. Person and tag filters are hard
     * AND'd together (multiple of the same kind means "all of them", not "any of them" — see
     * {@link ParsedSearchQuery}); any remaining free text then ranks the filtered set by cosine
     * similarity. A query with only {@code @}/{@code #} filters and no free text returns the
     * filtered set ordered by media ID descending (a recency proxy — there's no media-date field
     * plumbed through here) rather than by any similarity score, since there's no query text to
     * rank against.
     */
    public List<Long> search(ParsedSearchQuery parsed, int topK) throws Exception {
        Optional<Set<Long>> filtered = resolveFilteredPhotoIds(parsed);
        if (filtered.isPresent() && filtered.get()
                                            .isEmpty()) {
            // At least one @name or #tag didn't resolve to anything real — unsatisfiable.
            return List.of();
        }

        if (!parsed.hasFreeText()) {
            return filtered.map(ids -> ids.stream()
                                          .sorted(Comparator.reverseOrder())
                                          .collect(Collectors.toList()))
                           .orElse(List.of());
        }

        float[] textEmbedding = clipTextEncoder.encode(parsed.freeText());
        if (filtered.isEmpty()) {
            return clipVectorIndex.search(textEmbedding, topK);
        }

        Set<Long>  candidates      = filtered.get();
        List<Long> semanticResults = clipVectorIndex.search(textEmbedding, topK * 5);
        return semanticResults.stream()
                              .filter(candidates::contains)
                              .limit(topK)
                              .collect(Collectors.toList());
    }

    /**
     * Resolves the {@code @}/{@code #} filters to the media IDs matching ALL of them.
     * <p>
     * Returns {@link Optional#empty()} when there are no filters at all — callers should fall
     * back to unfiltered/plain semantic search in that case. A present-but-empty set specifically
     * means at least one referenced person or tag name doesn't exist, so the whole query is
     * unsatisfiable.
     */
    private Optional<Set<Long>> resolveFilteredPhotoIds(ParsedSearchQuery parsed) throws Exception {
        if (!parsed.hasFilters()) {
            return Optional.empty();
        }

        Set<Long> result = null;

        for (String name : parsed.personNames()) {
            Optional<Long> personId = personRepo.findIdByName(name);
            if (personId.isEmpty()) {
                log.warn("Search: no person named '{}'", name);
                return Optional.of(Set.of());
            }
            Set<Long> personPhotoIds = faceRepo.findByPersonId(personId.get())
                                               .stream()
                                               .map(FaceRecord::getMediaId)
                                               .collect(Collectors.toSet());
            result = intersect(result, personPhotoIds);
            if (result.isEmpty()) {
                return Optional.of(Set.of());
            }
        }

        if (!parsed.tagNames()
                   .isEmpty()) {
            Set<Long> tagIds = new HashSet<>();
            for (String tagName : parsed.tagNames()) {
                Optional<Long> tagId = photoTagRepo.findIdByTagName(tagName);
                if (tagId.isEmpty()) {
                    log.warn("Search: no tag named '{}'", tagName);
                    return Optional.of(Set.of());
                }
                tagIds.add(tagId.get());
            }
            result = intersect(result, photoTagRepo.findPhotoIdsHavingAllTags(tagIds));
        }

        return Optional.of(result == null ? Set.of() : result);
    }

    private static Set<Long> intersect(Set<Long> current, Set<Long> next) {
        if (current == null) {
            return new HashSet<>(next);
        }
        current.retainAll(next);
        return current;
    }
}
