# Face / Person Correction & Clustering — Requirements

## Background

The app detects faces (`RetinaFaceDetector`), encodes them (`ArcFaceEncoder`), and groups them
into `Person` records via `PersonClusteringService`. Clustering is currently **wipe-and-rebuild**:
every `AiPipelineJob` run clears all face→person assignments and re-clusters every embedding in
the database from scratch using greedy grouping (Pass 1) + iterative reassignment (Pass 2).
Clusters below `MIN_FACES_PER_PERSON` are dumped into a shared "Unknown" person.

Problems this causes:
- Detection/clustering is not perfect — faces are sometimes misidentified, split across multiple
  "people" who are actually the same person, or wrongly grouped together.
- There is currently **no UI mechanism** to correct any of this.
- Because clustering wipes and rebuilds everything on every run, any manual fix would be silently
  undone on the next AI pipeline run unless corrections are made durable ("sticky") by design.
- Full reclustering re-processes every embedding in the database every time, which gets more
  expensive as the library grows, even though most previously-clustered faces don't need to be
  re-evaluated.

## Goals

1. Let users correct face/person assignments through the UI.
2. Make those corrections durable — they must survive future AI pipeline / clustering runs.
3. Let corrections actively improve future automatic clustering (self-correcting over time),
   rather than just being a static exception list.
4. Support people whose faces don't all look alike (aging, glasses, lighting, etc.) via a
   **one person → multiple centroids ("prototypes")** model, instead of forcing every person to
   be representable by a single averaged centroid.
5. Move clustering from full wipe-and-rebuild on every run toward an **incremental** model, to
   keep routine imports cheap.

## Functional Requirements

### FR1 — Reassign a face to a different/new person
User can move a single misidentified face out of its current person and into a different
existing person, or into a brand-new person. Entry point: **Person album view** (per-person
screen), via a context menu on a face thumbnail (e.g. "Not this person…" → picker of existing
persons + "New person" option).

### FR2 — Confirm a face belongs to a person
User can explicitly confirm/lock a face's current assignment as correct. This is what protects
that face from being moved by future automatic clustering.

### FR3 — Split a person's faces into a separate person
User can select a subset of faces currently under one person and split them out into a different
existing person or a new one. Mechanically this is the same underlying action as FR1, applied to
a multi-selection (e.g. ctrl/shift-click in the grid + a "Move selected to…" toolbar action).

### FR4 — Merge two persons into one
User can merge Person B into Person A. All of B's face groups become part of A. B's row is not
deleted — it survives as a redirect to A, so old references still resolve correctly. This is a
person-level action (not per-face), e.g. via a button in the person header or drag-one-onto-
another in the person tree, with a confirmation dialog.

### FR5 — Mark a face as "not a person" / exclude
User can flag a detected face as a false positive (not actually a face, or not a person that
should be tracked). Excluded faces are removed from clustering entirely — they must not appear
as candidates in any person, including "Unknown."

### FR6 — One person, multiple centroids ("prototypes")
A person is not required to be represented by a single averaged centroid. Instead, a person owns
one or more **prototypes**, where each prototype is the centroid of one clustering group. A face
matches a person if it is close enough to *any one* of that person's prototypes (max similarity
across prototypes), not to a single blended average.

- Initially, a new person has exactly one prototype (one cluster = one person = one prototype).
- A person accumulates additional prototypes only when a human explicitly merges two separate
  groups/persons together (FR4) — i.e. multi-prototype is a direct, natural consequence of merge,
  not a separately-triggered process. No automatic sub-clustering or prototype-count capping is
  needed — prototypes map 1:1 onto clustering-formed groups.
- Merging two persons concatenates their prototype lists; centroids are **not** blended/averaged
  together, since that could produce a centroid that matches neither original appearance well.

### FR7 — Incremental clustering instead of full wipe-and-rebuild on every run
Replace "clear everything and recluster all embeddings" as the default per-pipeline-run behavior
with a hybrid model:

- **Fast/incremental path (runs on every `AiPipelineJob`)**: only newly-embedded faces since the
  last clustering pass are processed. Each new face is compared against existing **persisted**
  person prototypes (max similarity across a person's prototypes) and joins the best match above
  threshold, or seeds a new cluster/person if nothing matches. Locked/excluded faces are never
  touched or re-evaluated.
- **Full rebuild (explicit, user-triggered action, e.g. a "Recluster all" menu item)**: retains
  today's Pass 1 (greedy grouping) + Pass 2 (iterative reassignment) logic, but scoped to only
  unlocked, non-excluded faces. Locked faces' current prototypes seed Pass 1 as fixed attractors
  (see FR8) rather than being re-clustered themselves.

### FR8 — Corrections feed back into future clustering (self-correcting)
Prototypes are recomputed to reflect current group membership whenever it changes — not just
locked faces contribute; **every currently-assigned face in a group contributes to that group's
centroid**, consistent with how Pass 2's `recomputeCentroids()` already works today. Locking a
face only controls whether the algorithm is allowed to *move* it — it does not change whether
that face counts toward a centroid.

Recompute triggers:
- A face is locked/reassigned into a person (FR1/FR2/FR3)
- A face is excluded (FR5) — removed from its prototype's centroid computation
- A person merge occurs (FR4) — prototype lists are combined; no recompute of the centroids
  themselves is needed since prototypes aren't blended

This is what allows early mistakes to be self-correcting: once a person has one or more confirmed
groups, future faces of that person are more likely to auto-cluster correctly, because they're
tested against real, human-verified prototypes rather than an empty or noisy starting point.

## Non-Functional / Design Requirements

### NFR1 — Locking protects identity assignment, not centroid participation
`assignment_locked` (or equivalent) on a face means: this face's person assignment cannot be
changed by automatic clustering. It does **not** mean the face is excluded from centroid
computation — locked and unlocked faces both contribute to their group's centroid.

### NFR2 — Naming conventions
Repository methods that return a `Query` for batching (consistent with existing methods like
`assignPersonQuery`, `markFaceDetectDoneQuery`, `markErrorQuery`) must follow the `verb...Query`
naming pattern, e.g.:
- `lockFaceAssignmentQuery(faceId, personId)`
- `excludeFaceQuery(faceId)` / `includeFaceQuery(faceId)`
- `mergePersonQueries(sourcePersonId, targetPersonId)` → returns `List<Query>` since merge is
  multiple statements (reassign prototype ownership, set redirect)

Methods that need to execute immediately and return a generated value (consistent with
`personRepo.insert(...)`) do not need the `Query` suffix, e.g. creating a new blank person.

## Proposed Schema Changes

**`face` table** — add:
- `assignment_locked BOOLEAN NOT NULL DEFAULT FALSE` — true once a human explicitly
  assigns/confirms/moves this face (FR1, FR2, FR3)
- `excluded BOOLEAN NOT NULL DEFAULT FALSE` — true for faces marked "not a person" (FR5)
- `prototype_id BIGINT NULL` — which prototype (group) within the assigned person this face
  currently belongs to; used for centroid recompute bookkeeping

**`person` table** — add:
- `merged_into_id BIGINT NULL REFERENCES person(id)` — set when this person has been merged into
  another; the redirect must be followed wherever a person id is resolved (e.g. in
  `personRepo.findPersonIdOwningMostFaces`)

**New table `person_prototype`**:
- `id`
- `person_id` (FK)
- `centroid_embedding` (float array, same encoding as existing embedding columns)
- `member_count`

## Clustering Algorithm Changes (`PersonClusteringService`)

1. `faceEmbeddingRepo.findAll()` (or its incremental equivalent) must exclude faces where
   `excluded = true`.
2. Do not wipe assignments for faces where `assignment_locked = true`.
3. Locked faces are never included in Pass 1/Pass 2 candidate re-evaluation; each locked face's
   *current prototype centroid* seeds Pass 1 as a fixed attractor instead.
4. Unlocked faces are tested against all seeded + newly-formed centroids as today.
5. Persist step: each resulting cluster maps to one prototype. If the cluster resolves to an
   existing person (via the current "owns most faces" heuristic, following `merged_into_id`
   redirects), add/update that person's prototype for this cluster. Otherwise create a new person
   with one prototype.
6. The "Unknown" singleton bucket must exclude locked and excluded faces, same as the main pass.

## Explicitly Out of Scope (for this doc)

- Fixing the existing `LibraryController` FIXMEs (person albums not refreshing without app
  restart; missing thumbnail generation for person albums) — tracked separately, not a
  prerequisite for this feature.
- Automatic sub-clustering of a single group into multiple prototypes, or capping/merging
  "too-similar" prototypes — not needed, since prototypes map 1:1 to human-confirmed merges only.

## Open Implementation Questions (for the next chat to resolve)

- Exact UI treatment for the multi-select "split" flow in the photo grid (what indicates
  selection state, what the toolbar action looks like).
- Whether "Recluster all" (full rebuild) needs a progress/confirmation UI given it may be a
  heavier operation than routine incremental runs.
- How prototype seeding interacts with the incremental fast-path specifically (this doc describes
  it for the full-rebuild Pass 1; the incremental path's equivalent seeding logic needs to be
  spelled out in detail during implementation).
