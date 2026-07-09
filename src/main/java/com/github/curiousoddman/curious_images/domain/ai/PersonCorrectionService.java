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
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.github.curiousoddman.curious_images.persistence.ClipEmbeddingRepository.getFloats;

/**
 * Implements the human-correction actions from {@code face-person-correction-requirements.md}:
 * FR1 (reassign a face), FR2 (confirm a face), FR3 (split — same mechanics as FR1, applied to a
 * multi-selection), FR4 (merge two persons), and FR5 (exclude a face).
 * <p>
 * Every action here is what makes a correction "sticky" (durable across future clustering runs):
 * it either sets {@code assignment_locked = true} (FR1/FR2/FR3) so {@link PersonClusteringService}
 * never reassigns the face again, or removes the face from clustering consideration entirely
 * (FR5), or rewrites cluster ownership directly rather than touching individual faces (FR4).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonCorrectionService {

    private final FaceRepository            faceRepo;
    private final FaceEmbeddingRepository   faceEmbeddingRepo;
    private final PersonRepository          personRepo;
    private final ClusterRepository         clusterRepo;
    private final TimeProvider              timeProvider;
    private final DSLContext                dsl;
    private final ApplicationEventPublisher publisher;

    // ── FR1 / FR3 — reassign (single face or multi-select) to an existing person ─────────────

    /**
     * Moves {@code faceIds} out of whatever cluster(s) they're currently in and into
     * {@code targetPersonId}, locking each face so automatic clustering can never move it again.
     * The destination cluster is whichever of the target person's existing prototypes is
     * closest (by cosine similarity) to the average of the moved faces' embeddings — matching
     * FR6's "close enough to any one prototype" semantics — or a brand new prototype if the
     * target person doesn't have one yet.
     */
    // TODO: Unused???
    public void reassignFacesToExistingPerson(Collection<Long> faceIds, long targetPersonId) {
        if (faceIds.isEmpty()) {
            return;
        }
        LocalDateTime now = timeProvider.now();

        List<FaceRecord>   faces   = faceRepo.findByIds(faceIds);
        Map<Long, float[]> vectors = loadVectors(faceIds);
        if (vectors.isEmpty()) {
            log.warn("reassignFacesToExistingPerson: none of {} face(s) have an embedding yet — aborting", faceIds.size());
            return;
        }

        List<Query> buffer = new ArrayList<>();

        float[]                 referenceVector = EmbeddingMath.average(vectors.values());
        Optional<ClusterRecord> destCluster     = pickBestCluster(targetPersonId, referenceVector);

        long destClusterId;
        if (destCluster.isPresent()) {
            destClusterId = destCluster.get()
                                       .getId();
            addToCluster(destClusterId, vectors, now, buffer);
        } else {
            // Target person doesn't own a cluster yet — this becomes their first prototype.
            float[] centroid = referenceVector.clone();
            EmbeddingMath.l2Normalize(centroid);
            destClusterId = clusterRepo.insert(
                    targetPersonId, FaceEmbeddingRepository.toBytes(centroid), vectors.size(), now);
            for (Long faceId : vectors.keySet()) {
                buffer.add(faceRepo.lockFaceAssignmentQuery(faceId, destClusterId));
            }
        }
        removeFromOldClusters(faces, now, buffer);
        execute(buffer);
        publisher.publishEvent(new PersonsUpdatedEvent(this));
    }

    /**
     * Moves {@code faceIds} out of whatever cluster(s) they're currently in and into a brand new
     * person, locking each face. Mechanically identical for one face (FR1's "New person…" option)
     * or many (FR3's multi-select split).
     */
    public void reassignFacesToNewPerson(Collection<Long> faceIds, String newPersonName) {
        if (faceIds.isEmpty()) {
            return;
        }
        LocalDateTime now = timeProvider.now();

        List<FaceRecord>   faces   = faceRepo.findByIds(faceIds);
        Map<Long, float[]> vectors = loadVectors(faceIds);
        if (vectors.isEmpty()) {
            log.warn("reassignFacesToNewPerson: none of {} face(s) have an embedding yet — aborting", faceIds.size());
            return;
        }

        List<Query> buffer = new ArrayList<>();
        removeFromOldClusters(faces, now, buffer);

        float[] centroid    = EmbeddingMath.average(vectors.values());
        long    coverFaceId = mostCentralFaceId(vectors, centroid);

        long personId = personRepo.insert(newPersonName, coverFaceId, now);
        long newClusterId = clusterRepo.insert(
                personId, FaceEmbeddingRepository.toBytes(centroid), vectors.size(), now);
        for (Long faceId : vectors.keySet()) {
            buffer.add(faceRepo.lockFaceAssignmentQuery(faceId, newClusterId));
        }

        execute(buffer);
        publisher.publishEvent(new PersonsUpdatedEvent(this));
    }

    // ── FR2 — confirm a face's current assignment ─────────────────────────────────────────────

    /**
     * Locks a face's <em>current</em> assignment as correct. No-op with a warning if the face
     * isn't currently clustered — there's nothing to confirm.
     */
    public void confirmFace(long faceId) {
        Optional<FaceRecord> face = faceRepo.findById(faceId);
        if (face.isEmpty() || face.get()
                                  .getClusterId() == null) {
            log.warn("confirmFace: face {} is not currently assigned to anyone — nothing to confirm", faceId);
            return;
        }
        faceRepo.lockFaceAssignmentQuery(faceId, face.get()
                                                     .getClusterId())
                .execute();
    }

    // ── FR5 — exclude / include a face ────────────────────────────────────────────────────────

    /**
     * Flags a face as "not a person". Pulls it out of its current cluster (recomputing that
     * cluster's centroid, or deleting the cluster if this was its last member) so it stops
     * influencing anyone's prototype.
     */
    // TODO: unused?
    public void excludeFace(long faceId) {
        Optional<FaceRecord> face = faceRepo.findById(faceId);
        if (face.isEmpty()) {
            return;
        }
        LocalDateTime now    = timeProvider.now();
        List<Query>   buffer = new ArrayList<>();
        buffer.add(faceRepo.excludeFaceQuery(faceId));
        if (face.get()
                .getClusterId() != null) {
            removeFromCluster(face.get()
                                  .getClusterId(), Set.of(faceId), now, buffer);
        }
        execute(buffer);
        publisher.publishEvent(new PersonsUpdatedEvent(this));
    }

    /**
     * Reverses {@link #excludeFace}. The face re-enters the unclustered pool (unlocked, no
     * cluster) rather than being restored to wherever it used to be — the next clustering pass
     * re-evaluates it from scratch.
     */
    // TODO: Unused??
    public void includeFace(long faceId) {
        faceRepo.includeFaceQuery(faceId)
                .execute();
        publisher.publishEvent(new PersonsUpdatedEvent(this));
    }

    // ── FR4 — merge two persons ───────────────────────────────────────────────────────────────

    /**
     * Merges {@code sourcePersonId} into {@code targetPersonId}: every cluster the source person
     * owns is reassigned to the target in one statement (no face rows are touched — see
     * {@link ClusterRepository#reassignAllOwnedByQuery}), and the source person row is kept as a
     * redirect (FR4 — "B's row is not deleted"). Per FR6, centroids are concatenated, never
     * blended.
     */
    // TODO: Unused??
    public void mergePerson(long sourcePersonId, long targetPersonId) {
        if (sourcePersonId == targetPersonId) {
            log.warn("mergePerson: source and target are the same person ({}) — ignoring", sourcePersonId);
            return;
        }
        LocalDateTime now    = timeProvider.now();
        List<Query>   buffer = new ArrayList<>();
        buffer.add(clusterRepo.reassignAllOwnedByQuery(sourcePersonId, targetPersonId));
        buffer.add(personRepo.markMergedIntoQuery(sourcePersonId, targetPersonId, now));
        execute(buffer);
        publisher.publishEvent(new PersonsUpdatedEvent(this));
    }

    // ── Shared helpers ────────────────────────────────────────────────────────────────────────

    private Map<Long, float[]> loadVectors(Collection<Long> faceIds) {
        Map<Long, FaceEmbeddingRecord> records = faceEmbeddingRepo.findByFaceIds(faceIds);
        Map<Long, float[]>             vectors = new LinkedHashMap<>(records.size() * 2);
        for (Map.Entry<Long, FaceEmbeddingRecord> e : records.entrySet()) {
            vectors.put(e.getKey(), getFloats(e.getValue()
                                               .getEmbedding()));
        }
        return vectors;
    }

    /**
     * Groups {@code faces} by their current (pre-move) cluster and removes each group from it —
     * one recompute per affected cluster rather than one per face.
     */
    private void removeFromOldClusters(List<FaceRecord> faces, LocalDateTime now, List<Query> buffer) {
        Map<Long, List<Long>> byOldCluster = new LinkedHashMap<>();
        for (FaceRecord f : faces) {
            if (f.getClusterId() != null) {
                byOldCluster.computeIfAbsent(f.getClusterId(), k -> new ArrayList<>())
                            .add(f.getId());
            }
        }
        for (Map.Entry<Long, List<Long>> e : byOldCluster.entrySet()) {
            removeFromCluster(e.getKey(), new HashSet<>(e.getValue()), now, buffer);
        }
    }

    /**
     * Removes {@code removingFaceIds} from {@code clusterId}'s membership: recomputes the
     * centroid from whoever's left, or deletes the cluster outright if nobody's left (an
     * average-of-nothing is meaningless — see {@link ClusterRepository#deleteQuery}).
     */
    // FIXME: This should be more intelligent.
    // 1. it should ask if user wants to remove a person once person do not have any more photos after cluster removal
    // 2. if all cluster photos are moved to another person - it should assign cluster to a person, not just move photos into person
    // 3. when there are multiple clusters person already has - how should app decide to which moved faces go into?
    // 4. what does PersonDetailController::onMergeInto supposed to do? is this similar to 3?
    private void removeFromCluster(long clusterId, Set<Long> removingFaceIds, LocalDateTime now, List<Query> buffer) {
        List<FaceRecord> currentMembers = faceRepo.findByClusterId(clusterId);
        List<Long> remainingIds = currentMembers.stream()
                                                .map(FaceRecord::getId)
                                                .filter(id -> !removingFaceIds.contains(id))
                                                .toList();
        if (remainingIds.isEmpty()) {
            buffer.add(clusterRepo.deleteQuery(clusterId));
            return;
        }
        Map<Long, float[]> remainingVectors = loadVectors(remainingIds);
        if (remainingVectors.isEmpty()) {
            // Remaining members have no embeddings (shouldn't normally happen) — nothing sound to
            // recompute; leave the centroid as-is rather than guess.
            log.warn("removeFromCluster: cluster {} has {} remaining member(s) with no embeddings; " +
                    "leaving centroid unchanged", clusterId, remainingIds.size());
            return;
        }
        float[] centroid = EmbeddingMath.average(remainingVectors.values());
        buffer.add(clusterRepo.updateCentroidQuery(
                clusterId, FaceEmbeddingRepository.toBytes(centroid), remainingIds.size(), now));
    }

    /**
     * Adds {@code newVectors} to {@code clusterId}'s membership: recomputes the centroid from the
     * union of existing + new members, and locks each new face onto the cluster.
     */
    private void addToCluster(long clusterId, Map<Long, float[]> newVectors, LocalDateTime now, List<Query> buffer) {
        List<FaceRecord> currentMembers = faceRepo.findByClusterId(clusterId);
        List<Long> existingIds = currentMembers.stream()
                                               .map(FaceRecord::getId)
                                               .filter(id -> !newVectors.containsKey(id))
                                               .toList();
        Map<Long, float[]> existingVectors = loadVectors(existingIds);

        List<float[]> all = new ArrayList<>(existingVectors.size() + newVectors.size());
        all.addAll(existingVectors.values());
        all.addAll(newVectors.values());
        float[] centroid = EmbeddingMath.average(all);

        buffer.add(clusterRepo.updateCentroidQuery(
                clusterId, FaceEmbeddingRepository.toBytes(centroid), all.size(), now));
        for (Long faceId : newVectors.keySet()) {
            buffer.add(faceRepo.lockFaceAssignmentQuery(faceId, clusterId));
        }
    }

    /**
     * The best (max cosine similarity) existing cluster owned by {@code personId} for
     * {@code referenceVector} — empty if the person doesn't own any cluster yet.
     */
    private Optional<ClusterRecord> pickBestCluster(long personId, float[] referenceVector) {
        List<ClusterRecord> owned   = clusterRepo.findByPersonId(personId);
        ClusterRecord       best    = null;
        float               bestSim = -1f;
        for (ClusterRecord c : owned) {
            float sim = EmbeddingMath.dot(referenceVector, getFloats(c.getCentroidEmbedding()));
            if (sim > bestSim) {
                bestSim = sim;
                best = c;
            }
        }
        return Optional.ofNullable(best);
    }

    private long mostCentralFaceId(Map<Long, float[]> vectors, float[] centroid) {
        long  best    = vectors.keySet()
                               .iterator()
                               .next();
        float bestSim = -1f;
        for (Map.Entry<Long, float[]> e : vectors.entrySet()) {
            float sim = EmbeddingMath.dot(e.getValue(), centroid);
            if (sim > bestSim) {
                bestSim = sim;
                best = e.getKey();
            }
        }
        return best;
    }

    private void execute(List<Query> buffer) {
        if (buffer.isEmpty()) {
            return;
        }
        dsl.transaction(cfg -> DSL.using(cfg)
                                  .batch(buffer)
                                  .execute());
    }
}
