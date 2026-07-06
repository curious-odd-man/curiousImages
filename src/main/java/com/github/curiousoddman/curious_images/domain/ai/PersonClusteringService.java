package com.github.curiousoddman.curious_images.domain.ai;

import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceEmbeddingRecord;
import com.github.curiousoddman.curious_images.event.model.PersonsUpdatedEvent;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.github.curiousoddman.curious_images.persistence.ClipEmbeddingRepository.getFloats;

/**
 * Groups face embeddings into person candidates using centroid-based clustering over
 * cosine similarity (dot product on L2-normalised vectors).
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li><b>Wipe</b> — clear all face→person assignments (preserves Person rows and their
 *       name/dob/notes).</li>
 *   <li><b>Pass 1 — greedy grouping</b> — iterate faces in load order; find the closest
 *       existing cluster centroid above {@code SIMILARITY_THRESHOLD}; join it and update the
 *       centroid incrementally; otherwise start a new single-face cluster.</li>
 *   <li><b>Pass 2 — reassignment</b> — re-test every face against all final centroids and
 *       reassign to the closest one above threshold.  Repeat until no face changes cluster
 *       or {@code MAX_REASSIGNMENT_ITERATIONS} is reached (typically converges in 2–3
 *       rounds).</li>
 *   <li><b>Persist</b> — clusters with ≥ {@code MIN_FACES_PER_PERSON} faces are matched to
 *       an existing named Person row (whichever previously owned the most faces in the new
 *       cluster) or a new Person row is created.  The face nearest the centroid becomes
 *       {@code COVER_FACE_ID}.  Singletons are assigned to a shared "unknown" Person
 *       row.</li>
 * </ol>
 *
 * <h2>Complexity</h2>
 * O(n × g × iters) where g = number of clusters.  For 25 k faces and ~500 persons that is
 * roughly 25 000 × 500 × 3 ≈ 37 M dot-product operations — about 8× cheaper than the
 * previous O(n²) pairwise approach.
 *
 * <p>Called by {@link AiPipelineJob} after all face embeddings are generated.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonClusteringService {

    // ── Tunables ──────────────────────────────────────────────────────────────

    /**
     * Minimum cosine similarity for two faces to be considered the same person.
     */
    private static final float SIMILARITY_THRESHOLD = 0.4f;

    /**
     * Clusters smaller than this become singletons assigned to the "unknown" person.
     */
    private static final int MIN_FACES_PER_PERSON = 2;

    /**
     * Safety cap on reassignment loop iterations.
     */
    private static final int MAX_REASSIGNMENT_ITERS = 5;

    /**
     * Number of face→person UPDATE queries batched per DB round-trip.
     */
    private static final int DB_FLUSH_BATCH_SIZE = 200;

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final FaceEmbeddingRepository   faceEmbeddingRepo;
    private final FaceRepository            faceRepo;
    private final PersonRepository          personRepo;
    private final TimeProvider              timeProvider;
    private final DSLContext                dsl;
    private final ApplicationEventPublisher publisher;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Runs a full clustering pass.
     * <p>
     * All existing face→person assignments are cleared first so that the result is always
     * a clean, consistent clustering.  Person rows with human-curated data (name, dob,
     * notes) are preserved and re-linked where possible.
     */
    public void cluster() {
        log.info("Starting person clustering (centroid-based)…");

        List<FaceEmbeddingRecord> allEmbeddings = faceEmbeddingRepo.findAll();
        if (allEmbeddings.isEmpty()) {
            log.info("Person clustering: no face embeddings found");
            return;
        }

        int n = allEmbeddings.size();
        log.info("Clustering {} face embeddings (threshold={})", n, SIMILARITY_THRESHOLD);

        // ── 1. Load embeddings into parallel arrays ────────────────────────────
        float[][] vectors = new float[n][];
        long[]    faceIds = new long[n];
        for (int i = 0; i < n; i++) {
            FaceEmbeddingRecord r = allEmbeddings.get(i);
            faceIds[i] = r.getFaceId();
            vectors[i] = getFloats(r.getEmbedding());
        }

        // ── 2. Wipe existing assignments ──────────────────────────────────────
        Map<Long, Long> oldFaceToPersonSnapshot = faceRepo.loadFacePersonSnapshot();
        faceRepo.clearAllPersonAssignments();

        // ── 3. Pass 1: greedy grouping ────────────────────────────────────────
        //
        // clusterOf[i]  → which cluster index face i belongs to (-1 = unassigned)
        // centroids      → current L2-normalised centroid per cluster
        // clusterSizes   → number of faces already in each cluster (for incremental update)

        int[]         clusterOf    = new int[n];
        List<float[]> centroids    = new ArrayList<>();
        List<Integer> clusterSizes = new ArrayList<>();

        java.util.Arrays.fill(clusterOf, -1);

        for (int i = 0; i < n; i++) {
            int   bestCluster = -1;
            float bestSim     = SIMILARITY_THRESHOLD; // must exceed threshold to join

            for (int c = 0; c < centroids.size(); c++) {
                float sim = dot(vectors[i], centroids.get(c));
                if (sim > bestSim) {
                    bestSim = sim;
                    bestCluster = c;
                }
            }

            if (bestCluster == -1) {
                // Start a new cluster seeded by this face
                centroids.add(vectors[i].clone());
                clusterSizes.add(1);
                clusterOf[i] = centroids.size() - 1;
            } else {
                clusterOf[i] = bestCluster;
                updateCentroid(centroids, clusterSizes, bestCluster, vectors[i]);
            }
        }

        log.info("Pass 1 complete: {} initial clusters formed", centroids.size());

        // ── 4. Pass 2: iterative reassignment ────────────────────────────────
        //
        // Re-test every face against the (now stable) centroids.  Rebuild centroids from
        // scratch each iteration to avoid drift from the incremental update order.

        for (int iter = 0; iter < MAX_REASSIGNMENT_ITERS; iter++) {
            int changes = 0;

            for (int i = 0; i < n; i++) {
                int   bestCluster = -1;
                float bestSim     = SIMILARITY_THRESHOLD;

                for (int c = 0; c < centroids.size(); c++) {
                    float sim = dot(vectors[i], centroids.get(c));
                    if (sim > bestSim) {
                        bestSim = sim;
                        bestCluster = c;
                    }
                }

                // A face that was above threshold in pass 1 but is now below it becomes a
                // singleton (bestCluster == -1 handled as its own 1-face cluster below).
                int newCluster = (bestCluster == -1) ? clusterOf[i] : bestCluster;
                if (newCluster != clusterOf[i]) {
                    clusterOf[i] = newCluster;
                    changes++;
                }
            }

            // Recompute centroids from scratch based on new assignments
            recomputeCentroids(centroids, clusterSizes, clusterOf, vectors, n);

            log.debug("Reassignment iteration {}: {} faces moved", iter + 1, changes);
            if (changes == 0) {
                log.info("Reassignment converged after {} iteration(s)", iter + 1);
                break;
            }
        }

        // ── 5. Collect clusters ───────────────────────────────────────────────

        int numClusters = centroids.size();
        @SuppressWarnings("unchecked")
        List<Integer>[] clusterMembers = new List[numClusters];
        for (int c = 0; c < numClusters; c++) {
            clusterMembers[c] = new ArrayList<>();
        }
        for (int i = 0; i < n; i++) {
            clusterMembers[clusterOf[i]].add(i);
        }

        // ── 6. Persist ────────────────────────────────────────────────────────

        LocalDateTime now             = timeProvider.now();
        List<Query>   buffer          = new ArrayList<>(DB_FLUSH_BATCH_SIZE);
        int           personsCreated  = 0;
        int           singletonsCount = 0;
        Long          unknownPersonId = null; // lazily created

        for (int c = 0; c < numClusters; c++) {
            List<Integer> members = clusterMembers[c];

            if (members.size() < MIN_FACES_PER_PERSON) {
                // Singleton — defer until we know we need the unknown bucket
                singletonsCount += members.size();
                continue;
            }

            // Find the face closest to the centroid — it becomes COVER_FACE_ID
            float[] centroid    = centroids.get(c);
            long    coverFaceId = faceIds[mostCentralIndex(members, vectors, centroid)];

            // Re-use an existing named person if possible, otherwise create a new one
            List<Long>     memberFaceIds    = memberFaceIds(members, faceIds);
            Optional<Long> existingPersonId = personRepo.findPersonIdOwningMostFaces(memberFaceIds, oldFaceToPersonSnapshot);

            long personId;
            if (existingPersonId.isPresent()) {
                personId = existingPersonId.get();
                personRepo.updateCoverFace(personId, coverFaceId, now);
            } else {
                personId = personRepo.insert(null, coverFaceId, now);
                personsCreated++;
            }

            for (int idx : members) {
                buffer.add(faceRepo.assignPersonQuery(faceIds[idx], personId));
                if (buffer.size() >= DB_FLUSH_BATCH_SIZE) {
                    flush(buffer);
                }
            }
        }

        // Assign singletons to the shared unknown person
        if (singletonsCount > 0) {
            unknownPersonId = personRepo.findOrCreateUnknown(now);
            for (int c = 0; c < numClusters; c++) {
                List<Integer> members = clusterMembers[c];
                if (members.size() >= MIN_FACES_PER_PERSON) {
                    continue;
                }
                for (int idx : members) {
                    buffer.add(faceRepo.assignPersonQuery(faceIds[idx], unknownPersonId));
                    if (buffer.size() >= DB_FLUSH_BATCH_SIZE) {
                        flush(buffer);
                    }
                }
            }
            log.info("{} singleton face(s) assigned to unknown person id={}", singletonsCount, unknownPersonId);
        }

        flush(buffer);

        log.info(
                "Person clustering complete: {} new person(s) created, {} existing person(s) relinked, " +
                        "{} singleton(s), {} total faces",
                personsCreated,
                (numClusters - personsCreated - (singletonsCount > 0 ? 1 : 0)),
                singletonsCount,
                n
        );

        publisher.publishEvent(new PersonsUpdatedEvent(this));
    }

    // ── Centroid helpers ──────────────────────────────────────────────────────

    /**
     * Incremental centroid update: moves the centroid toward {@code v} by one step and
     * re-normalises so subsequent dot products remain cosine similarities.
     */
    private void updateCentroid(List<float[]> centroids, List<Integer> sizes, int c, float[] v) {
        float[] centroid = centroids.get(c);
        int     oldSize  = sizes.get(c);
        int     newSize  = oldSize + 1;
        float   scale    = 1.0f / newSize;

        for (int k = 0; k < centroid.length; k++) {
            centroid[k] = (centroid[k] * oldSize + v[k]) * scale;
        }
        l2Normalize(centroid);
        sizes.set(c, newSize);
    }

    /**
     * Rebuilds all centroids from scratch based on current {@code clusterOf} assignments.
     * Called after each reassignment iteration to prevent order-dependent drift.
     */
    private void recomputeCentroids(
            List<float[]> centroids,
            List<Integer> sizes,
            int[] clusterOf,
            float[][] vectors,
            int n
    ) {
        int dim = vectors[0].length;
        for (int c = 0; c < centroids.size(); c++) {
            centroids.set(c, new float[dim]);
            sizes.set(c, 0);
        }
        for (int i = 0; i < n; i++) {
            int     c        = clusterOf[i];
            float[] centroid = centroids.get(c);
            for (int k = 0; k < dim; k++) {
                centroid[k] += vectors[i][k];
            }
            sizes.set(c, sizes.get(c) + 1);
        }
        for (int c = 0; c < centroids.size(); c++) {
            float[] centroid = centroids.get(c);
            float   scale    = (sizes.get(c) > 0) ? 1.0f / sizes.get(c) : 1.0f;
            for (int k = 0; k < centroid.length; k++) {
                centroid[k] *= scale;
            }
            l2Normalize(centroid);
        }
    }

    /**
     * Returns the index (within {@code members}) of the face whose vector is closest to
     * {@code centroid}.  This face becomes the cluster's cover image.
     */
    private int mostCentralIndex(List<Integer> members, float[][] vectors, float[] centroid) {
        int   best    = 0;
        float bestSim = -1f;
        for (int rank = 0; rank < members.size(); rank++) {
            float sim = dot(vectors[members.get(rank)], centroid);
            if (sim > bestSim) {
                bestSim = sim;
                best = rank;
            }
        }
        return members.get(best);
    }

    private List<Long> memberFaceIds(List<Integer> members, long[] faceIds) {
        List<Long> ids = new ArrayList<>(members.size());
        for (int idx : members) {
            ids.add(faceIds[idx]);
        }
        return ids;
    }

    // ── Maths ─────────────────────────────────────────────────────────────────

    /**
     * Dot product of two vectors — equals cosine similarity when both are L2-normalised.
     */
    private float dot(float[] a, float[] b) {
        float sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    /**
     * In-place L2 normalisation. No-op if the vector is the zero vector.
     */
    private void l2Normalize(float[] v) {
        float norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        if (norm == 0) {
            return;
        }
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < v.length; i++) {
            v[i] /= norm;
        }
    }

    // ── DB helpers ────────────────────────────────────────────────────────────

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
