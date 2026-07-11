package com.github.curiousoddman.curious_images.domain.ai;

import com.github.curiousoddman.curious_images.dbobj.tables.records.ClusterRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceEmbeddingRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceRecord;
import com.github.curiousoddman.curious_images.event.model.PersonsUpdatedEvent;
import com.github.curiousoddman.curious_images.persistence.ClusterRepository;
import com.github.curiousoddman.curious_images.persistence.FaceEmbeddingRepository;
import com.github.curiousoddman.curious_images.persistence.FaceRepository;
import com.github.curiousoddman.curious_images.persistence.PersonRepository;
import com.github.curiousoddman.curious_images.util.EmbeddingMath;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.github.curiousoddman.curious_images.persistence.ClipEmbeddingRepository.getFloats;

/**
 * Groups face embeddings into person candidates using centroid-based clustering over cosine
 * similarity (dot product on L2-normalised vectors).
 * <p>
 * A person owns one or more {@code cluster} rows (the requirements doc's "prototypes"); a face
 * matches a person if it's close enough to <em>any</em> of that person's cluster centroids. There
 * is no single blended-average centroid per person — see {@code face-person-correction-requirements.md}
 * FR6.
 *
 * <h2>Two entry points (FR7)</h2>
 * <ul>
 *   <li>{@link #clusterIncremental()} — cheap, runs on every {@link AiPipelineJob}. Only
 *       currently-unclustered faces are processed, each tested against every existing persisted
 *       cluster and joining the best match above threshold, or immediately seeding a brand new
 *       person + single-member cluster if nothing matches. This means a very first bulk import
 *       (before any prototypes exist yet) will mint one person per face rather than grouping
 *       them — by design, per the requirements doc: the fast-path never runs the expensive
 *   mutual-grouping search, that's what {@link #reclusterAll()} is for.</li>
 *   <li>{@link #reclusterAll()} — the explicit, user-triggered "Recluster all" action. Runs the
 *       original Pass 1 (greedy grouping) + Pass 2 (iterative reassignment) over every unlocked,
 *       non-excluded face, so faces that incremental runs turned into separate singleton people
 *       get a chance to merge into proper multi-face clusters. Locked faces are never
 *       reassigned, but each of their current clusters still seeds Pass 1 as a real (not
 *       zero-initialised) centroid, and — like every other cluster — that centroid keeps moving
 *       as membership changes across Pass 1/2 (every currently-assigned face, locked or not,
 *       contributes to its cluster's centroid — FR8/NFR1).</li>
 * </ul>
 *
 * <p>Excluded faces ({@code face.excluded = true}) are never loaded by either path.
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
     * In a full rebuild, brand-new (non-locked-seeded) clusters smaller than this stay
     * unclustered ("Unknown") rather than becoming a named person. Locked-seeded clusters are
     * exempt from this check regardless of size — a human already confirmed that identity.
     */
    private static final int MIN_FACES_PER_PERSON = 2;

    /**
     * Safety cap on reassignment loop iterations.
     */
    private static final int MAX_REASSIGNMENT_ITERS = 5;

    /**
     * Number of queries batched per DB round-trip.
     */
    private static final int DB_FLUSH_BATCH_SIZE = 200;

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final FaceEmbeddingRepository   faceEmbeddingRepo;
    private final FaceRepository            faceRepo;
    private final PersonRepository          personRepo;
    private final ClusterRepository         clusterRepo;
    private final TimeProvider              timeProvider;
    private final DSLContext                dsl;
    private final ApplicationEventPublisher publisher;

    // ── Incremental fast-path (FR7) ──────────────────────────────────────────

    /**
     * Runs the cheap incremental pass. Safe (and intended) to call on every {@link AiPipelineJob}
     * run, however few faces are pending.
     */
    public void clusterIncremental() {
        log.info("Starting incremental person clustering…");

        List<FaceRecord> pending = faceRepo.findUnclustered();
        if (pending.isEmpty()) {
            log.info("Incremental clustering: no unclustered faces");
            return;
        }

        List<Long> pendingIds = pending.stream()
                                       .map(FaceRecord::getId)
                                       .toList();
        Map<Long, FaceEmbeddingRecord> embeddingsByFace = faceEmbeddingRepo.findByFaceIds(pendingIds);

        // Load every existing prototype as an in-memory match candidate (kept in memory for the
        // whole pass rather than re-queried per face).
        List<Long>    clusterIds = new ArrayList<>();
        List<float[]> centroids  = new ArrayList<>();
        List<Integer> sizes      = new ArrayList<>();
        for (ClusterRecord c : clusterRepo.findAll()) {
            if (c.getMemberCount() == null || c.getMemberCount() <= 0) {
                continue; // stale/orphaned — never a valid match candidate
            }
            clusterIds.add(c.getId());
            centroids.add(getFloats(c.getCentroidEmbedding()));
            sizes.add(c.getMemberCount());
        }

        LocalDateTime now     = timeProvider.now();
        List<Query>   buffer  = new ArrayList<>(DB_FLUSH_BATCH_SIZE);
        int           matched = 0;
        int           seeded  = 0;
        int           skipped = 0;

        for (FaceRecord face : pending) {
            FaceEmbeddingRecord embRec = embeddingsByFace.get(face.getId());
            if (embRec == null) {
                skipped++; // no embedding yet — not actually ready for clustering
                continue;
            }
            float[] v = getFloats(embRec.getEmbedding());

            int   best    = -1;
            float bestSim = SIMILARITY_THRESHOLD;
            for (int c = 0; c < centroids.size(); c++) {
                float sim = EmbeddingMath.dot(v, centroids.get(c));
                if (sim > bestSim) {
                    bestSim = sim;
                    best = c;
                }
            }

            long clusterId;
            if (best != -1) {
                clusterId = clusterIds.get(best);
                float[] centroid = centroids.get(best);
                int     oldSize  = sizes.get(best);
                EmbeddingMath.incrementalUpdate(centroid, oldSize, v);
                sizes.set(best, oldSize + 1);
                buffer.add(clusterRepo.updateCentroidQuery(
                        clusterId, FaceEmbeddingRepository.toBytes(centroid), oldSize + 1, now));
                matched++;
            } else {
                // Nothing matched — seed a brand new person + single-member cluster immediately.
                long personId = personRepo.insert(null, face.getId(), now);
                clusterId = clusterRepo.insert(personId, FaceEmbeddingRepository.toBytes(v), 1, now);
                clusterIds.add(clusterId);
                centroids.add(v.clone());
                sizes.add(1);
                seeded++;
            }

            buffer.add(faceRepo.assignClusterQuery(face.getId(), clusterId));
            if (buffer.size() >= DB_FLUSH_BATCH_SIZE) {
                flush(buffer);
            }
        }

        flush(buffer);
        log.info("Incremental clustering complete: {} face(s) matched an existing person, " +
                        "{} new person(s) seeded, {} face(s) skipped (no embedding yet)",
                matched, seeded, skipped);
        publisher.publishEvent(new PersonsUpdatedEvent(this));
    }

    // ── Full rebuild (explicit "Recluster all" action) ───────────────────────

    /**
     * Runs a full recluster over every unlocked, non-excluded face. Locked faces keep their
     * current cluster untouched in the sense that they can never be <em>reassigned away</em>
     * from it, but their cluster's centroid is still recomputed as usual if other (unlocked)
     * faces join or leave it during this pass.
     */
    public void reclusterAll() {
        log.info("Starting full person recluster…");

        // ── 1. Snapshot continuity info + wipe unlocked assignments ───────────
        Map<Long, Long> oldFaceToPersonSnapshot = faceRepo.loadFacePersonSnapshot();
        faceRepo.clearUnlockedClusterAssignments();

        List<FaceRecord> lockedFaces   = faceRepo.findLockedNonExcluded();
        List<FaceRecord> unlockedFaces = faceRepo.findUnlockedNonExcluded();

        if (lockedFaces.isEmpty() && unlockedFaces.isEmpty()) {
            log.info("Full recluster: no faces to process");
            return;
        }

        List<Long> allIds = new ArrayList<>(lockedFaces.size() + unlockedFaces.size());
        lockedFaces.forEach(f -> allIds.add(f.getId()));
        unlockedFaces.forEach(f -> allIds.add(f.getId()));
        Map<Long, FaceEmbeddingRecord> embeddingsByFace = faceEmbeddingRepo.findByFaceIds(allIds);

        int n = lockedFaces.size() + unlockedFaces.size();
        log.info("Full recluster over {} face(s) ({} locked, {} unlocked, threshold={})",
                n, lockedFaces.size(), unlockedFaces.size(), SIMILARITY_THRESHOLD);

        float[][] vectors   = new float[n][];
        long[]    faceIds   = new long[n];
        boolean[] isLocked  = new boolean[n];
        int[]     clusterOf = new int[n];
        java.util.Arrays.fill(clusterOf, -1);

        List<float[]> centroids            = new ArrayList<>();
        List<Integer> clusterSizes         = new ArrayList<>();
        List<Long>    existingClusterIdOf  = new ArrayList<>(); // parallel to centroids; null = brand new
        List<Long>    existingClusterOwner = new ArrayList<>(); // parallel to centroids; owning personId if existing

        // ── 2. Seed one centroid per distinct locked cluster, from the true average of its
        //        locked members (not incremental — we want the exact starting point). ─────────
        Map<Long, List<Integer>> lockedIndicesByClusterId = new LinkedHashMap<>();
        for (int i = 0; i < lockedFaces.size(); i++) {
            FaceRecord          f      = lockedFaces.get(i);
            FaceEmbeddingRecord embRec = embeddingsByFace.get(f.getId());
            faceIds[i] = f.getId();
            isLocked[i] = true;
            vectors[i] = (embRec != null) ? getFloats(embRec.getEmbedding()) : new float[0];
            if (f.getClusterId() != null && embRec != null) {
                lockedIndicesByClusterId.computeIfAbsent(f.getClusterId(), k -> new ArrayList<>())
                                        .add(i);
            }
        }

        Map<Long, Integer> clusterListIndexByClusterId = new HashMap<>();
        for (Map.Entry<Long, List<Integer>> entry : lockedIndicesByClusterId.entrySet()) {
            long          existingClusterId = entry.getKey();
            List<Integer> indices           = entry.getValue();

            List<float[]> memberVectors = new ArrayList<>(indices.size());
            for (int idx : indices) {
                memberVectors.add(vectors[idx]);
            }
            float[] centroid = EmbeddingMath.average(memberVectors);

            Long ownerPersonId = clusterRepo.findById(existingClusterId)
                                            .map(ClusterRecord::getPersonId)
                                            .orElse(null);

            int listIndex = centroids.size();
            centroids.add(centroid);
            clusterSizes.add(indices.size());
            existingClusterIdOf.add(existingClusterId);
            existingClusterOwner.add(ownerPersonId);
            clusterListIndexByClusterId.put(existingClusterId, listIndex);

            for (int idx : indices) {
                clusterOf[idx] = listIndex;
            }
        }

        // ── 3. Pass 1 — greedy grouping, unlocked faces only ──────────────────
        int unlockedStart = lockedFaces.size();
        for (int j = 0; j < unlockedFaces.size(); j++) {
            int                 i      = unlockedStart + j;
            FaceRecord          f      = unlockedFaces.get(j);
            FaceEmbeddingRecord embRec = embeddingsByFace.get(f.getId());
            faceIds[i] = f.getId();
            isLocked[i] = false;
            if (embRec == null) {
                vectors[i] = new float[0];
                continue; // no embedding yet — leave unclustered (clusterOf stays -1 → singleton)
            }
            vectors[i] = getFloats(embRec.getEmbedding());

            int   bestCluster = -1;
            float bestSim     = SIMILARITY_THRESHOLD;
            for (int c = 0; c < centroids.size(); c++) {
                float sim = EmbeddingMath.dot(vectors[i], centroids.get(c));
                if (sim > bestSim) {
                    bestSim = sim;
                    bestCluster = c;
                }
            }

            if (bestCluster == -1) {
                centroids.add(vectors[i].clone());
                clusterSizes.add(1);
                existingClusterIdOf.add(null);
                existingClusterOwner.add(null);
                clusterOf[i] = centroids.size() - 1;
            } else {
                clusterOf[i] = bestCluster;
                updateCentroidIncremental(centroids, clusterSizes, bestCluster, vectors[i]);
            }
        }

        log.info("Pass 1 complete: {} cluster(s) in play ({} locked-seeded)",
                centroids.size(), lockedIndicesByClusterId.size());

        // ── 4. Pass 2 — iterative reassignment, unlocked faces only; centroids recomputed from
        //        every currently-assigned face (locked + unlocked) each iteration (FR8). ───────
        for (int iter = 0; iter < MAX_REASSIGNMENT_ITERS; iter++) {
            int changes = 0;
            for (int j = 0; j < unlockedFaces.size(); j++) {
                int i = unlockedStart + j;
                if (vectors[i].length == 0) {
                    continue; // no embedding — never participates in reassignment
                }

                int   bestCluster = -1;
                float bestSim     = SIMILARITY_THRESHOLD;
                for (int c = 0; c < centroids.size(); c++) {
                    float sim = EmbeddingMath.dot(vectors[i], centroids.get(c));
                    if (sim > bestSim) {
                        bestSim = sim;
                        bestCluster = c;
                    }
                }
                int newCluster = (bestCluster == -1) ? clusterOf[i] : bestCluster;
                if (newCluster != clusterOf[i]) {
                    clusterOf[i] = newCluster;
                    changes++;
                }
            }

            recomputeCentroidsFromScratch(centroids, clusterSizes, clusterOf, vectors, n);

            log.debug("Reassignment iteration {}: {} face(s) moved", iter + 1, changes);
            if (changes == 0) {
                log.info("Reassignment converged after {} iteration(s)", iter + 1);
                break;
            }
        }

        // ── 5. Collect final cluster membership ───────────────────────────────
        int numClusters = centroids.size();
        @SuppressWarnings("unchecked")
        List<Integer>[] members = new List[numClusters];
        for (int c = 0; c < numClusters; c++) {
            members[c] = new ArrayList<>();
        }
        for (int i = 0; i < n; i++) {
            if (clusterOf[i] >= 0) {
                members[clusterOf[i]].add(i);
            }
        }

        // ── 6. Persist ────────────────────────────────────────────────────────
        LocalDateTime now             = timeProvider.now();
        List<Query>   buffer          = new ArrayList<>(DB_FLUSH_BATCH_SIZE);
        int           personsCreated  = 0;
        int           clustersUpdated = 0;
        int           singletonsCount = 0;

        for (int c = 0; c < numClusters; c++) {
            List<Integer> memberIdx     = members[c];
            Long          seedClusterId = existingClusterIdOf.get(c);

            if (seedClusterId != null) {
                // Locked-seeded cluster — always persists, size exemption included.
                Long    personId = existingClusterOwner.get(c);
                float[] centroid = centroids.get(c);
                buffer.add(clusterRepo.updateCentroidQuery(
                        seedClusterId, FaceEmbeddingRepository.toBytes(centroid), memberIdx.size(), now));
                clustersUpdated++;

                for (int idx : memberIdx) {
                    if (!isLocked[idx]) {
                        buffer.add(faceRepo.assignClusterQuery(faceIds[idx], seedClusterId));
                    }
                }
                if (personId != null) {
                    long coverFaceId = faceIds[mostCentralIndex(memberIdx, vectors, centroid)];
                    maybeSetCoverFace(personId, coverFaceId, now);
                }
            } else if (memberIdx.size() >= MIN_FACES_PER_PERSON) {
                // Brand new cluster formed purely from unlocked faces this run.
                float[]    centroid      = centroids.get(c);
                List<Long> memberFaceIds = memberFaceIds(memberIdx, faceIds);
                long       coverFaceId   = faceIds[mostCentralIndex(memberIdx, vectors, centroid)];

                Optional<Long> existingPersonId =
                        personRepo.findPersonIdOwningMostFaces(memberFaceIds, oldFaceToPersonSnapshot);

                long personId = existingPersonId.orElseGet(() -> personRepo.insert(null, coverFaceId, now));
                if (existingPersonId.isEmpty()) {
                    personsCreated++;
                }

                long newClusterId = clusterRepo.insert(
                        personId, FaceEmbeddingRepository.toBytes(centroid), memberIdx.size(), now);
                for (int idx : memberIdx) {
                    buffer.add(faceRepo.assignClusterQuery(faceIds[idx], newClusterId));
                }
                maybeSetCoverFace(personId, coverFaceId, now);
            } else {
                // Unlocked singleton — stays unclustered ("Unknown"); cluster_id is already NULL.
                singletonsCount += memberIdx.size();
            }

            if (buffer.size() >= DB_FLUSH_BATCH_SIZE) {
                flush(buffer);
            }
        }

        flush(buffer);

        log.info("Full recluster complete: {} new person(s) created, {} existing cluster(s) " +
                        "updated, {} singleton(s) left unclustered, {} total face(s) processed",
                personsCreated, clustersUpdated, singletonsCount, n);

        publisher.publishEvent(new PersonsUpdatedEvent(this));
    }

    /**
     * Only sets the person's cover face if it doesn't already have one — a full rebuild shouldn't
     * clobber a cover a human already picked (see {@code PersonDetailController#onBrowseFaces}).
     */
    private void maybeSetCoverFace(long personId, long candidateFaceId, LocalDateTime now) {
        personRepo.findById(personId)
                  .filter(p -> p.getCoverFaceId() == null)
                  .ifPresent(p -> personRepo.updateCoverFace(personId, candidateFaceId, now));
    }

    // ── Centroid helpers ──────────────────────────────────────────────────────

    private void updateCentroidIncremental(List<float[]> centroids, List<Integer> sizes, int c, float[] v) {
        float[] centroid = centroids.get(c);
        int     oldSize  = sizes.get(c);
        EmbeddingMath.incrementalUpdate(centroid, oldSize, v);
        sizes.set(c, oldSize + 1);
    }

    /**
     * Rebuilds all centroids from scratch based on current {@code clusterOf} assignments —
     * including locked faces, which never change {@code clusterOf} but still contribute to
     * whichever centroid they're pinned to (FR8).
     */
    private void recomputeCentroidsFromScratch(
            List<float[]> centroids, List<Integer> sizes, int[] clusterOf, float[][] vectors, int n) {
        int dim = -1;
        for (float[] v : vectors) {
            if (v.length > 0) {
                dim = v.length;
                break;
            }
        }
        if (dim == -1) {
            return; // nothing has an embedding — nothing to recompute
        }
        for (int c = 0; c < centroids.size(); c++) {
            centroids.set(c, new float[dim]);
            sizes.set(c, 0);
        }
        for (int i = 0; i < n; i++) {
            int c = clusterOf[i];
            if (c < 0 || vectors[i].length == 0) {
                continue;
            }
            float[] centroid = centroids.get(c);
            for (int k = 0; k < dim; k++) {
                centroid[k] += vectors[i][k];
            }
            sizes.set(c, sizes.get(c) + 1);
        }
        for (int c = 0; c < centroids.size(); c++) {
            float[] centroid = centroids.get(c);
            int     size     = sizes.get(c);
            float   scale    = (size > 0) ? 1.0f / size : 1.0f;
            for (int k = 0; k < centroid.length; k++) {
                centroid[k] *= scale;
            }
            EmbeddingMath.l2Normalize(centroid);
        }
    }

    private int mostCentralIndex(List<Integer> members, float[][] vectors, float[] centroid) {
        int   best    = 0;
        float bestSim = -1f;
        for (int rank = 0; rank < members.size(); rank++) {
            int idx = members.get(rank);
            if (vectors[idx].length == 0) {
                continue;
            }
            float sim = EmbeddingMath.dot(vectors[idx], centroid);
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
