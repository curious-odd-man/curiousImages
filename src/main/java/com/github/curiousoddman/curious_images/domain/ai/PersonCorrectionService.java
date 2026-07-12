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
 * <p>
 * <b>Whole vs. partial moves (FR6).</b> When a correction moves faces out of a cluster, this
 * service distinguishes two cases. If <em>every</em> face currently in the source cluster is
 * moving at once, that cluster is already a complete, human-verified prototype — it is relocated
 * intact via {@link ClusterRepository#reassignOwnerQuery} (its centroid is never touched or
 * blended). If only a subset moves, the remainder's centroid is recomputed and the moved subset
 * either folds into an existing destination cluster the caller specifies, or seeds a brand new
 * one. This service deliberately does <b>not</b> auto-pick that destination cluster by cosine
 * similarity — {@link #suggestDestinationCluster} exposes the same similarity heuristic purely as
 * a suggestion for the UI to pre-select in a picker; the human makes the actual call.
 * <p>
 * <b>Orphaned persons (open question 1 from the requirements doc).</b> A correction can leave a
 * person owning zero clusters (e.g. their only cluster got fully reassigned or excluded away).
 * This service never deletes that person on its own initiative — methods that can cause this
 * return the set of newly-orphaned person IDs so the UI can ask the user, then call
 * {@link #deleteOrphanedPerson} if they confirm.
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
     * Moves {@code faceIds} into {@code targetPersonId}, locking each face so automatic
     * clustering can never move it again.
     * <p>
     * {@code destinationClusterId} is the human's explicit choice of which of the target
     * person's existing prototypes the moved (partial-move) faces should join — see
     * {@link #suggestDestinationCluster} for a similarity-based suggestion the UI can pre-select
     * — or {@code null} to seed a brand new prototype for the target person instead. This choice
     * only matters for a <em>partial</em> move: if a whole source cluster is moving as one unit,
     * it is relocated intact (see class javadoc) regardless of {@code destinationClusterId},
     * since blending an already-complete prototype into another would violate FR6.
     *
     * @return IDs of any persons left owning zero clusters as a side effect of this move (a
     * source cluster's whole membership left and that was its owner's last cluster) — the UI
     * should ask the user whether to delete them via {@link #deleteOrphanedPerson}.
     * @throws IllegalArgumentException if {@code destinationClusterId} is given but isn't a
     *                                  cluster owned by {@code targetPersonId}.
     */
    public Set<Long> reassignFacesToExistingPerson(Collection<Long> faceIds, long targetPersonId, Long destinationClusterId) {
        if (faceIds.isEmpty()) {
            return Set.of();
        }
        LocalDateTime now = timeProvider.now();

        List<FaceRecord>   faces   = faceRepo.findByIds(faceIds);
        Map<Long, float[]> vectors = loadVectors(faceIds);
        if (vectors.isEmpty()) {
            log.warn("reassignFacesToExistingPerson: none of {} face(s) have an embedding yet — aborting", faceIds.size());
            return Set.of();
        }
        if (destinationClusterId != null) {
            ClusterRecord dest = clusterRepo.findById(destinationClusterId)
                                            .orElseThrow(() -> new IllegalArgumentException(
                                                    "No such cluster: " + destinationClusterId));
            if (dest.getPersonId() != targetPersonId) {
                throw new IllegalArgumentException(
                        "Cluster " + destinationClusterId + " is not owned by person " + targetPersonId);
            }
        }

        List<Query> buffer          = new ArrayList<>();
        Set<Long>   orphanedPersons = new HashSet<>();

        // Faces that end up needing a destination decision (i.e. weren't handled by a whole-
        // cluster relocation below) and, per source cluster, which of its members are staying
        // behind and need that cluster's centroid recomputed.
        Map<Long, float[]>    needsDestination     = new LinkedHashMap<>();
        Map<Long, List<Long>> partialRemovalsByOld = new LinkedHashMap<>();

        Map<Long, List<FaceRecord>> byOldCluster = new LinkedHashMap<>();
        for (FaceRecord f : faces) {
            if (f.getClusterId() != null) {
                byOldCluster.computeIfAbsent(f.getClusterId(), k -> new ArrayList<>())
                            .add(f);
            } else {
                needsDestination.put(f.getId(), vectors.get(f.getId()));
            }
        }

        for (Map.Entry<Long, List<FaceRecord>> e : byOldCluster.entrySet()) {
            long             oldClusterId = e.getKey();
            List<FaceRecord> moving       = e.getValue();
            List<FaceRecord> allMembers   = faceRepo.findByClusterId(oldClusterId);

            if (allMembers.size() == moving.size()) {
                // Whole cluster relocates — carry the prototype over intact (FR6).
                ClusterRecord source = clusterRepo.findById(oldClusterId)
                                                  .orElseThrow();
                long previousOwner = source.getPersonId();
                buffer.add(clusterRepo.reassignOwnerQuery(oldClusterId, targetPersonId));
                for (FaceRecord f : moving) {
                    buffer.add(faceRepo.lockFaceAssignmentQuery(f.getId(), oldClusterId));
                }
                if (previousOwner != targetPersonId) {
                    long remaining = clusterRepo.findByPersonId(previousOwner)
                                                .stream()
                                                .filter(c -> c.getId() != oldClusterId)
                                                .count();
                    if (remaining == 0) {
                        orphanedPersons.add(previousOwner);
                    }
                }
            } else {
                partialRemovalsByOld.put(oldClusterId, moving.stream()
                                                             .map(FaceRecord::getId)
                                                             .toList());
                for (FaceRecord f : moving) {
                    needsDestination.put(f.getId(), vectors.get(f.getId()));
                }
            }
        }

        if (!needsDestination.isEmpty()) {
            if (destinationClusterId != null) {
                addToCluster(destinationClusterId, needsDestination, now, buffer);
            } else {
                // No existing prototype chosen — this partial subset seeds a brand new one.
                float[] centroid = EmbeddingMath.average(needsDestination.values());
                EmbeddingMath.l2Normalize(centroid);
                long newClusterId = clusterRepo.insert(
                        targetPersonId, FaceEmbeddingRepository.toBytes(centroid), needsDestination.size(), now);
                for (Long faceId : needsDestination.keySet()) {
                    buffer.add(faceRepo.lockFaceAssignmentQuery(faceId, newClusterId));
                }
            }
        }

        for (Map.Entry<Long, List<Long>> e : partialRemovalsByOld.entrySet()) {
            removeFromCluster(e.getKey(), new HashSet<>(e.getValue()), now, buffer)
                    .ifPresent(orphanedPersons::add);
        }

        execute(buffer);
        publisher.publishEvent(new PersonsUpdatedEvent(this));
        return orphanedPersons;
    }

    /**
     * Similarity-based suggestion only — the caller (UI) decides whether to actually use it. The
     * best (max cosine similarity) existing cluster owned by {@code targetPersonId} for the
     * average of {@code faceIds}' embeddings, to pre-select as a default in a destination picker
     * alongside the target person's other prototypes and a "new prototype" option. Empty if the
     * person owns no cluster yet, or none of {@code faceIds} have embeddings.
     */
    public Optional<ClusterRecord> suggestDestinationCluster(long targetPersonId, Collection<Long> faceIds) {
        Map<Long, float[]> vectors = loadVectors(faceIds);
        if (vectors.isEmpty()) {
            return Optional.empty();
        }
        float[] referenceVector = EmbeddingMath.average(vectors.values());
        return pickBestCluster(targetPersonId, referenceVector);
    }

    // ── Used by PhotoRotationService — manual rotation correction wipes a photo's faces ───────

    /**
     * Wipes clustering involvement for every one of {@code faceIds} — recomputes or deletes
     * whichever cluster(s) they belonged to — without assigning them anywhere new. Used by
     * {@code PhotoRotationService} right before it deletes the corresponding {@code FACE} rows
     * outright: a photo whose rotation was manually corrected has meaningless bounding
     * boxes/embeddings for its existing faces, so unlike FR1/FR3/FR5 there is no "new home" for
     * them — they're simply going away. Unclustered IDs in {@code faceIds} are silently skipped
     * (nothing to clean up for them).
     * <p>
     * Must be called <em>before</em> the caller deletes the {@code FACE} rows — this method reads
     * each face's current {@code cluster_id} via {@link FaceRepository#findByIds}, which returns
     * nothing for rows that no longer exist.
     *
     * @return newly-orphaned person IDs — same semantics as {@link #reassignFacesToExistingPerson}
     * etc. This method never deletes an orphaned person itself; see {@link #deleteOrphanedPerson}.
     */
    public Set<Long> removeFacesFromClusters(Collection<Long> faceIds) {
        if (faceIds.isEmpty()) {
            return Set.of();
        }
        LocalDateTime    now   = timeProvider.now();
        List<FaceRecord> faces = faceRepo.findByIds(faceIds);

        Map<Long, Set<Long>> byCluster = new LinkedHashMap<>();
        for (FaceRecord f : faces) {
            if (f.getClusterId() != null) {
                byCluster.computeIfAbsent(f.getClusterId(), k -> new HashSet<>())
                         .add(f.getId());
            }
        }

        List<Query> buffer   = new ArrayList<>();
        Set<Long>   orphaned = new HashSet<>();
        for (Map.Entry<Long, Set<Long>> e : byCluster.entrySet()) {
            removeFromCluster(e.getKey(), e.getValue(), now, buffer)
                    .ifPresent(orphaned::add);
        }
        execute(buffer);
        publisher.publishEvent(new PersonsUpdatedEvent(this));
        return orphaned;
    }

    /**
     * Moves {@code faceIds} out of whatever cluster(s) they're currently in and into a brand new
     * person, locking each face. Mechanically identical for one face (FR1's "New person…" option)
     * or many (FR3's multi-select split).
     * <p>
     * If {@code faceIds} turns out to be <em>exactly</em> one whole existing cluster (every face
     * that cluster currently has, nothing more, nothing less), that cluster is relocated to the
     * new person intact (FR6) instead of being dissolved and rebuilt from the same vectors as a
     * freshly-averaged one.
     *
     * @return IDs of any persons left owning zero clusters as a side effect (only possible via the
     * whole-cluster-relocation path) — see {@link #deleteOrphanedPerson}.
     */
    public Set<Long> reassignFacesToNewPerson(Collection<Long> faceIds, String newPersonName) {
        if (faceIds.isEmpty()) {
            return Set.of();
        }
        LocalDateTime now = timeProvider.now();

        List<FaceRecord>   faces   = faceRepo.findByIds(faceIds);
        Map<Long, float[]> vectors = loadVectors(faceIds);
        if (vectors.isEmpty()) {
            log.warn("reassignFacesToNewPerson: none of {} face(s) have an embedding yet — aborting", faceIds.size());
            return Set.of();
        }

        List<Query> buffer   = new ArrayList<>();
        Set<Long>   orphaned = new HashSet<>();

        Long soleWholeSourceClusterId = soleWholeSourceCluster(faces, vectors.keySet());
        long personId;

        if (soleWholeSourceClusterId != null) {
            ClusterRecord source = clusterRepo.findById(soleWholeSourceClusterId)
                                              .orElseThrow();
            long previousOwner = source.getPersonId();
            long coverFaceId   = mostCentralFaceId(vectors, getFloats(source.getCentroidEmbedding()));

            personId = personRepo.insert(newPersonName, coverFaceId, now);
            buffer.add(clusterRepo.reassignOwnerQuery(soleWholeSourceClusterId, personId));
            for (Long faceId : vectors.keySet()) {
                buffer.add(faceRepo.lockFaceAssignmentQuery(faceId, soleWholeSourceClusterId));
            }
            long remaining = clusterRepo.findByPersonId(previousOwner)
                                        .stream()
                                        .filter(c -> !c.getId()
                                                       .equals(soleWholeSourceClusterId))
                                        .count();
            if (remaining == 0) {
                orphaned.add(previousOwner);
            }
        } else {
            orphaned.addAll(removeFromOldClusters(faces, now, buffer));

            float[] centroid    = EmbeddingMath.average(vectors.values());
            long    coverFaceId = mostCentralFaceId(vectors, centroid);

            personId = personRepo.insert(newPersonName, coverFaceId, now);
            long newClusterId = clusterRepo.insert(
                    personId, FaceEmbeddingRepository.toBytes(centroid), vectors.size(), now);
            for (Long faceId : vectors.keySet()) {
                buffer.add(faceRepo.lockFaceAssignmentQuery(faceId, newClusterId));
            }
        }

        execute(buffer);
        publisher.publishEvent(new PersonsUpdatedEvent(this));
        return orphaned;
    }

    /**
     * Returns the single cluster ID being moved, if and only if every face in {@code faces}
     * shares that one cluster <em>and</em> {@code movingFaceIds} is that cluster's entire current
     * membership — i.e. this is cleanly "relocate this whole prototype," not a partial split or a
     * mashup of several clusters/unclustered faces. Null otherwise.
     */
    private Long soleWholeSourceCluster(List<FaceRecord> faces, Set<Long> movingFaceIds) {
        Long clusterId = null;
        for (FaceRecord f : faces) {
            if (f.getClusterId() == null) {
                return null;
            }
            if (clusterId == null) {
                clusterId = f.getClusterId();
            } else if (!clusterId.equals(f.getClusterId())) {
                return null;
            }
        }
        if (clusterId == null) {
            return null;
        }
        List<FaceRecord> allMembers = faceRepo.findByClusterId(clusterId);
        return allMembers.size() == movingFaceIds.size() ? clusterId : null;
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
     *
     * @return the owning person's ID if excluding this face left them owning zero clusters —
     * see {@link #deleteOrphanedPerson}.
     */
    public Optional<Long> excludeFace(long faceId) {
        Optional<FaceRecord> face = faceRepo.findById(faceId);
        if (face.isEmpty()) {
            return Optional.empty();
        }
        LocalDateTime  now      = timeProvider.now();
        List<Query>    buffer   = new ArrayList<>();
        Optional<Long> orphaned = Optional.empty();
        buffer.add(faceRepo.excludeFaceQuery(faceId));
        if (face.get()
                .getClusterId() != null) {
            orphaned = removeFromCluster(face.get()
                                             .getClusterId(), Set.of(faceId), now, buffer);
        }
        execute(buffer);
        publisher.publishEvent(new PersonsUpdatedEvent(this));
        return orphaned;
    }

    /**
     * Reverses {@link #excludeFace}. The face re-enters the unclustered pool (unlocked, no
     * cluster) rather than being restored to wherever it used to be — the next clustering pass
     * re-evaluates it from scratch.
     */
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
     * one recompute per affected cluster rather than one per face. Only ever reaches a "whole
     * cluster emptied" outcome here for callers that don't already special-case a whole-cluster
     * move (see {@link #reassignFacesToExistingPerson}, which handles that case separately via
     * {@link ClusterRepository#reassignOwnerQuery} instead of calling this at all for that
     * subset); {@link #reassignFacesToNewPerson} and {@link #excludeFace} still route through
     * here for their non-whole-cluster paths.
     *
     * @return IDs of persons left owning zero clusters as a result
     * on {@link #removeFromCluster}.
     */
    private Set<Long> removeFromOldClusters(List<FaceRecord> faces, LocalDateTime now, List<Query> buffer) {
        Map<Long, List<Long>> byOldCluster = new LinkedHashMap<>();
        for (FaceRecord f : faces) {
            if (f.getClusterId() != null) {
                byOldCluster.computeIfAbsent(f.getClusterId(), k -> new ArrayList<>())
                            .add(f.getId());
            }
        }
        Set<Long> orphaned = new HashSet<>();
        for (Map.Entry<Long, List<Long>> e : byOldCluster.entrySet()) {
            removeFromCluster(e.getKey(), new HashSet<>(e.getValue()), now, buffer)
                    .ifPresent(orphaned::add);
        }
        return orphaned;
    }

    /**
     * Removes {@code removingFaceIds} from {@code clusterId}'s membership: recomputes the
     * centroid from whoever's left, or deletes the cluster outright if nobody's left (an
     * average-of-nothing is meaningless — see {@link ClusterRepository#deleteQuery}).
     * <p>
     * <ol>
     *   <li>Whether to remove a person left with zero clusters is a UI decision, not this
     *       service's to make — this method (and every public method that calls it) surfaces the
     *       owning person's ID via its return value instead of guessing; see
     *       {@link #deleteOrphanedPerson}.</li>
     *   <li>Handled one level up: {@link #reassignFacesToExistingPerson} and
     *       {@link #reassignFacesToNewPerson} detect a whole-cluster move before ever calling
     *       this method, and relocate the cluster via {@link ClusterRepository#reassignOwnerQuery}
     *       instead — this method only ever runs for genuinely partial removals now.</li>
     *   <li>Not this method's concern — see {@link #suggestDestinationCluster}: the human picks,
     *       this service only offers a similarity-based suggestion.</li>
     *   <li>Answered separately — {@code PersonDetailController#onMergeInto} is FR4's
     *       person-level merge, unrelated to the per-face destination question above; see its
     *       javadoc.</li>
     * </ol>
     *
     * @return the owning person's ID, if removing these faces left the cluster with no members
     * (and it was deleted) and that person now owns no cluster at all; empty otherwise.
     */
    private Optional<Long> removeFromCluster(long clusterId, Set<Long> removingFaceIds, LocalDateTime now, List<Query> buffer) {
        List<FaceRecord> currentMembers = faceRepo.findByClusterId(clusterId);
        List<Long> remainingIds = currentMembers.stream()
                                                .map(FaceRecord::getId)
                                                .filter(id -> !removingFaceIds.contains(id))
                                                .toList();
        if (remainingIds.isEmpty()) {
            Optional<ClusterRecord> cluster = clusterRepo.findById(clusterId);
            buffer.add(clusterRepo.deleteQuery(clusterId));
            if (cluster.isEmpty()) {
                return Optional.empty();
            }
            long personId = cluster.get()
                                   .getPersonId();
            long remaining = clusterRepo.findByPersonId(personId)
                                        .stream()
                                        .filter(c -> c.getId() != clusterId)
                                        .count();
            return remaining == 0 ? Optional.of(personId) : Optional.empty();
        }
        Map<Long, float[]> remainingVectors = loadVectors(remainingIds);
        if (remainingVectors.isEmpty()) {
            // Remaining members have no embeddings (shouldn't normally happen) — nothing sound to
            // recompute; leave the centroid as-is rather than guess.
            log.warn("removeFromCluster: cluster {} has {} remaining member(s) with no embeddings; " +
                    "leaving centroid unchanged", clusterId, remainingIds.size());
            return Optional.empty();
        }
        float[] centroid = EmbeddingMath.average(remainingVectors.values());
        buffer.add(clusterRepo.updateCentroidQuery(
                clusterId, FaceEmbeddingRepository.toBytes(centroid), remainingIds.size(), now));
        return Optional.empty();
    }

    // ── Q1 — orphaned-person cleanup (UI-confirmed only) ──────────────────────────────────────

    /**
     * Deletes a person who currently owns zero clusters — the confirmed follow-up to an
     * orphaned-person ID returned by {@link #reassignFacesToExistingPerson},
     * {@link #reassignFacesToNewPerson}, or {@link #excludeFace}. This service never calls this
     * itself; it is meant to be invoked only after the UI has asked the user and gotten an
     * explicit "yes, delete this empty person" confirmation.
     * <p>
     * Refuses (returns {@code false}, logs a warning) if the person still owns a cluster — the
     * caller's orphan detection is stale — or if another person is still redirecting into this
     * one via merge ({@code merged_into_id}), since deleting a live merge target would break
     * {@link PersonRepository#resolveCurrentPersonId} for those sources.
     */
    public boolean deleteOrphanedPerson(long personId) {
        if (!clusterRepo.findByPersonId(personId)
                        .isEmpty()) {
            log.warn("deleteOrphanedPerson: person {} still owns cluster(s) — refusing to delete", personId);
            return false;
        }
        List<Long> mergeSources = personRepo.findMergeSourceIds(personId);
        if (!mergeSources.isEmpty()) {
            log.warn("deleteOrphanedPerson: person {} is still a live merge-redirect target for {} — refusing to delete",
                    personId, mergeSources);
            return false;
        }
        personRepo.deleteQuery(personId)
                  .execute();
        publisher.publishEvent(new PersonsUpdatedEvent(this));
        return true;
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
     * {@code referenceVector} — empty if the person doesn't own any cluster yet. Internal helper
     * for {@link #suggestDestinationCluster} only; nothing here auto-applies this as a decision.
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
        long best = vectors.keySet()
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
