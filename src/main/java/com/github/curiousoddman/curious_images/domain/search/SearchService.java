package com.github.curiousoddman.curious_images.domain.search;

import com.github.curiousoddman.curious_images.dbobj.tables.records.ClipEmbeddingRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceRecord;
import com.github.curiousoddman.curious_images.domain.ai.ClipTextEncoder;
import com.github.curiousoddman.curious_images.domain.index.ClipVectorIndex;
import com.github.curiousoddman.curious_images.persistence.ClipEmbeddingRepository;
import com.github.curiousoddman.curious_images.persistence.FaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Exposes semantic text-to-image search and similar-photo discovery backed by the
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

    /**
     * Encodes {@code query} with the CLIP text encoder and returns up to {@code topK} photo
     * IDs ordered by descending cosine similarity.
     */
    public List<Long> semanticSearch(String query, int topK) throws Exception {
        float[] textEmbedding = clipTextEncoder.encode(query);
        return clipVectorIndex.search(textEmbedding, topK);
    }

    /**
     * Returns up to {@code topK} photo IDs visually similar to {@code photoId}, excluding
     * the query photo itself.
     */
    public List<Long> similarPhotos(long photoId, int topK) throws Exception {
        ClipEmbeddingRecord rec = clipEmbeddingRepo.findByPhotoId(photoId)
                                                   .orElseThrow(() -> new IllegalStateException(
                                                           "No CLIP embedding for photo " + photoId +
                                                                   " — run the AI pipeline first."));
        float[]    embedding = ClipEmbeddingRepository.getFloats(rec.getEmbedding());
        List<Long> results   = clipVectorIndex.search(embedding, topK + 1);
        results = results.stream()
                         .filter(id -> id != photoId)
                         .limit(topK)
                         .collect(Collectors.toList());
        return results;
    }

    /**
     * Combined person + semantic search: returns photos of {@code personId} that also match
     * {@code semanticQuery}, ranked by semantic similarity.
     */
    public List<Long> combinedSearch(long personId, String semanticQuery, int topK) throws Exception {
        Set<Long> personPhotos = faceRepo.findByPersonId(personId)
                                         .stream()
                                         .map(FaceRecord::getPhotoId)
                                         .collect(Collectors.toSet());
        float[]    textEmbedding   = clipTextEncoder.encode(semanticQuery);
        List<Long> semanticResults = clipVectorIndex.search(textEmbedding, topK * 5);
        return semanticResults.stream()
                              .filter(personPhotos::contains)
                              .limit(topK)
                              .collect(Collectors.toList());
    }
}
