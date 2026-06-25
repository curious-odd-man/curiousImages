package com.github.curiousoddman.curious_images.domain.ai;

import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceEmbeddingRecord;
import com.github.curiousoddman.curious_images.event.PersonsUpdatedEvent;
import com.github.curiousoddman.curious_images.persistence.FaceEmbeddingRepository;
import com.github.curiousoddman.curious_images.persistence.FaceRepository;
import com.github.curiousoddman.curious_images.persistence.PersonRepository;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.impl.DSL;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.curiousoddman.curious_images.persistence.ClipEmbeddingRepository.getFloats;

/**
 * Groups face embeddings into person candidates using greedy union-find clustering over
 * cosine similarity (equivalent to dot product on L2-normalised vectors).
 * <p>
 * For collections ≤ 10,000 faces the O(n²) pairwise loop is used. For larger collections
 * the Lucene face vector index should be wired in here as a KNN-per-face approximation
 * (not yet implemented — extend this when needed).
 * <p>
 * Called by {@link AiPipelineJob} after all face embeddings are generated.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonClusteringService {

    private static final float SIMILARITY_THRESHOLD = 0.4f;
    private static final int   MIN_FACES_PER_PERSON = 2;
    private static final int   PAIRWISE_SIZE_LIMIT  = 10_000;
    private static final int   DB_FLUSH_BATCH_SIZE  = 200;

    private final FaceEmbeddingRepository   faceEmbeddingRepo;
    private final FaceRepository            faceRepo;
    private final PersonRepository          personRepo;
    private final TimeProvider              timeProvider;
    private final DSLContext                dsl;
    private final ApplicationEventPublisher publisher;

    /**
     * Runs a full clustering pass. All existing person assignments are left in place; this
     * method only creates new person rows and assigns previously unassigned faces. Call after
     * every pipeline run or whenever embeddings change.
     */
    public void cluster() {
        log.info("Starting person clustering...");
        List<FaceEmbeddingRecord> allEmbeddings = faceEmbeddingRepo.findAll();
        if (allEmbeddings.isEmpty()) {
            log.info("Person clustering: no face embeddings found");
            return;
        }

        int n = allEmbeddings.size();
        log.info("Clustering {} face embeddings (threshold={})", n, SIMILARITY_THRESHOLD);

        // Convert stored bytes → float arrays once
        float[][] vectors = new float[n][];
        long[]    faceIds = new long[n];
        for (int i = 0; i < n; i++) {
            faceIds[i] = allEmbeddings.get(i)
                                      .getFaceId();
            vectors[i] = getFloats(allEmbeddings.get(i)
                                                .getEmbedding());
        }

        // Union-Find
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }

        if (n <= PAIRWISE_SIZE_LIMIT) {
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    float sim = cosineSim(vectors[i], vectors[j]);
                    if (sim > SIMILARITY_THRESHOLD) {
                        union(parent, i, j);
                    }
                }
            }
        } else {
            log.warn("More than {} face embeddings — pairwise clustering skipped; " +
                    "wire in Lucene KNN approximation for production use.", PAIRWISE_SIZE_LIMIT);
        }

        // Group by root
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>())
                  .add(i);
        }

        LocalDateTime now            = timeProvider.now();
        List<Query>   buffer         = new ArrayList<>(DB_FLUSH_BATCH_SIZE);
        int           personsCreated = 0;

        for (List<Integer> group : groups.values()) {
            if (group.size() < MIN_FACES_PER_PERSON) {
                continue;
            }

            // Assign all faces to a new person
            long personId = personRepo.insert(null, faceIds[group.get(0)], now);
            personsCreated++;

            for (int idx : group) {
                buffer.add(faceRepo.assignPersonQuery(faceIds[idx], personId));
                if (buffer.size() >= DB_FLUSH_BATCH_SIZE) {
                    flush(buffer);
                }
            }
        }
        flush(buffer);

        log.info("Person clustering complete: {} persons created from {} faces",
                personsCreated, n);
        publisher.publishEvent(new PersonsUpdatedEvent(this));
    }

    // ── Union-Find ────────────────────────────────────────────────────────────

    private int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]]; // path compression
            x = parent[x];
        }
        return x;
    }

    private void union(int[] parent, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) {
            parent[ra] = rb;
        }
    }

    // ── Maths ─────────────────────────────────────────────────────────────────

    /**
     * Cosine similarity of two L2-normalised vectors = their dot product.
     */
    private float cosineSim(float[] a, float[] b) {
        float dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
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
}
