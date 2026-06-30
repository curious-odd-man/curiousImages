# Use Case Catalogue — curious-images
*Format: Given / When / Then. Intended as a base for e2e / integration tests.*

---

## 1. Import — File Discovery & Persistence

### UC-01 New photo is imported for the first time
**Given** an import root pointing to a folder containing `photo.jpg`
**When** the import scan runs
**Then** a `PHOTO` row is created with correct `absolute_path`, `filename`, `extension`, `file_size`, `folder_id`, `imported_at`, and all `ai_*_done` flags set to `false`

---

### UC-02 Unchanged photo on rescan is skipped
**Given** `photo.jpg` was already imported with file size = 100 kB
**When** the import scan runs again and the file still has size = 100 kB
**Then** only `last_seen_at` is updated; all other fields and AI flags remain unchanged

---

### UC-03 Modified photo on rescan resets AI flags for that photo only
**Given** `photo.jpg` was already imported and fully AI-processed (`ai_*_done` all `true`)
**When** the file is replaced with a new version (different file size) and the import scan runs
**Then** metadata columns are updated, all `ai_*_done` flags are reset to `false` for **that photo only**, and no other photo's flags are affected

> ⚠️ This test is expected to **fail** until the missing `WHERE` clause in `resetAiFields()` is fixed (Issue #1).

---

### UC-04 File with unsupported extension is ignored
**Given** a folder containing `document.pdf` and `photo.jpg`
**When** the import scan runs
**Then** only `photo.jpg` is persisted; no `PHOTO` row exists for `document.pdf`

---

### UC-05 Folder hierarchy is preserved
**Given** a root folder `A/` with subfolders `A/B/` and `A/B/C/` each containing one photo
**When** the import scan runs
**Then** three `FOLDER` rows are created reflecting the hierarchy, and each photo's `folder_id` points to the correct folder

---

### UC-06 Import scan interrupted midway leaves completed work intact
**Given** a folder with 100 photos and an import scan in progress
**When** the user requests cancellation after 50 photos
**Then** the 50 already-imported photos remain in the database, and the 50 unprocessed photos have no `PHOTO` rows

---

### UC-07 Second import scan is rejected while first is running
**Given** an import scan is already running
**When** a second import scan is triggered (via `RescanLibraryEvent` or `startMultiRootScan`)
**Then** the second scan is silently rejected (warning logged), and only one scan thread runs

---

### UC-08 Multi-root scan imports all roots sequentially
**Given** two separate root folders each containing photos
**When** `startMultiRootScan` is called with both paths
**Then** all photos from both roots are imported, and a single `LibraryUpdatedEvent` is published after both roots complete

---

## 2. Add Files Workflow

### UC-09 Copy-then-import: files are copied before scanning
**Given** a source folder `src/` with photos and a destination `dest/` with `copyToDestination = true`
**When** `AddFilesService.start()` is called
**Then** files appear in `dest/<src-folder-name>/`, and those paths are imported into the library

---

### UC-10 Copy-then-import: existing file with same size is not overwritten
**Given** `dest/photo.jpg` already exists with size = 100 kB, and source `src/photo.jpg` also has size = 100 kB
**When** `AddFilesService.start()` runs with `copyToDestination = true`
**Then** `dest/photo.jpg` is not overwritten (file bytes unchanged), but the photo is still scanned and its `PHOTO` row is touched

---

### UC-11 In-place add: source paths are scanned without copying
**Given** `copyToDestination = false` and a list of source paths
**When** `AddFilesService.start()` runs
**Then** the source paths themselves are registered as scan roots; no files are copied

---

### UC-12 AddFilesService is rejected if ImportService is already running
**Given** `ImportService` is currently running a scan
**When** `AddFilesService.start()` is called
**Then** the method returns `false` immediately; no new thread is started

---

### UC-13 Post-import AI pipeline is triggered when requested
**Given** `runAiPipeline = true` in the request
**When** `AddFilesService` completes its scan
**Then** a `RunAiPipelineEvent` is published; the AI pipeline eventually starts

---

### UC-14 Post-import duplicate detection is triggered when requested
**Given** `runDuplicateDetection = true` in the request
**When** `AddFilesService` completes its scan
**Then** `DuplicateDetectionService.start()` is called; duplicate detection eventually starts

---

## 3. AI Pipeline

### UC-15 Face detection creates face rows and marks photo done
**Given** a photo with one visible face has been imported
**When** the AI pipeline runs the face detection stage
**Then** one `FACE` row is created with bounding box and landmark columns populated, and `ai_face_detect_done = true` on the `PHOTO` row

---

### UC-16 Photo with no faces still gets marked as face-detection done
**Given** a photo with no visible faces
**When** the AI pipeline runs face detection
**Then** no `FACE` rows are created, and `ai_face_detect_done = true`

---

### UC-17 Face embedding creates embedding rows for all faces
**Given** a photo with two faces and `ai_face_detect_done = true`
**When** the face embedding stage runs
**Then** two `FACE_EMBEDDING` rows are created (one per face), and `ai_face_embed_done = true`

---

### UC-18 CLIP embedding is generated independently of face pipeline
**Given** a photo with `ai_clip_embed_done = false` (regardless of face status)
**When** the CLIP embedding stage runs
**Then** a `CLIP_EMBEDDING` row is created and `ai_clip_embed_done = true`

---

### UC-19 Lucene indexing happens after both embeddings are ready
**Given** a photo with `ai_clip_embed_done = true` and `ai_lucene_index_done = false`
**When** the Lucene indexing stage runs
**Then** the CLIP vector is written to the Lucene index and `ai_lucene_index_done = true`

---

### UC-20 Corrupt / undecodable photo is not retried indefinitely
**Given** an undecodable photo file
**When** the AI pipeline runs
**Then** `ai_retry_count` is incremented and `ai_last_error` is set; the photo is **not** reattempted on subsequent pipeline runs once `ai_retry_count` exceeds a threshold

> ⚠️ This test is expected to **fail** — there is no retry cap currently (Issue #2).

---

### UC-21 Pipeline is fully resumable after crash
**Given** a library with 100 photos and the AI pipeline completed 50 photos before a crash
**When** the AI pipeline runs again
**Then** only the 50 remaining photos are processed; the 50 already-done photos are untouched

---

### UC-22 AI pipeline does not start a second concurrent run
**Given** the AI pipeline is already running
**When** a second `RunAiPipelineEvent` is published
**Then** the second run is rejected (warning logged); only one pipeline thread runs at a time

---

## 4. Person Clustering

### UC-23 Two similar faces are clustered into one person
**Given** two face embeddings with cosine similarity > 0.4
**When** `PersonClusteringService.cluster()` runs
**Then** both face rows have the same `person_id`; one `PERSON` row is created

---

### UC-24 Singleton face is assigned to the shared "unknown" person
**Given** one face embedding with no neighbours above the similarity threshold
**When** clustering runs
**Then** the face is assigned to the "unknown" `PERSON` row (name = null, or named "unknown")

---

### UC-25 Existing named person is preserved across re-clustering
**Given** a cluster of 5 faces previously linked to a `PERSON` row with `name = "Alice"`, and a new set of face embeddings
**When** clustering runs again
**Then** `PERSON.name` for Alice's row is still `"Alice"` and her faces are re-linked to the same person id

---

### UC-26 Cover face is the face nearest the cluster centroid
**Given** a cluster of 3 faces with known embeddings
**When** clustering runs
**Then** `person.cover_face_id` points to the face whose embedding is closest (highest cosine similarity) to the cluster centroid

---

## 5. Album Generation

### UC-27 Person albums are created for each named person with photos
**Given** two persons with photos in the library after clustering
**When** album generation runs
**Then** one `ALBUM` row of type `PERSON` exists for each person, and `ALBUM_PHOTO` rows link the correct photos

---

### UC-28 Event albums group photos within the time gap
**Given** 10 photos taken within 1 hour, then a 12-hour gap, then 10 more photos
**When** album generation runs with `eventGapHours = 6` and `minEventSize = 5`
**Then** two `EVENT` albums are created, each containing 10 photos

---

### UC-29 Event album with fewer photos than `minEventSize` is not created
**Given** 3 photos taken within 1 hour (below `minEventSize = 5`)
**When** album generation runs
**Then** no `EVENT` album is created for that group

---

### UC-30 Location albums group photos by GPS cell
**Given** 5 photos with GPS coordinates rounding to the same 2-decimal cell, and 2 photos rounding to a different cell
**When** album generation runs with `minLocationSize = 3`
**Then** one `LOCATION` album is created for the group of 5; the group of 2 is ignored

---

### UC-31 Album regeneration deletes all old albums of each type before inserting new ones
**Given** PERSON, EVENT, LOCATION, SIMILARITY albums from a previous run
**When** album generation runs again
**Then** all old albums of each type are deleted within the same transaction before new ones are inserted; no duplicate albums exist

---

### UC-32 Photos without a capture date are excluded from event albums
**Given** a mix of photos with and without `capture_date`
**When** event albums are generated
**Then** only photos with non-null `capture_date` appear in any event album

---

## 6. Duplicate Detection

### UC-33 Two photos with identical pixel content and same extension form a duplicate group
**Given** two JPEG files that decode to identical pixels
**When** duplicate detection runs
**Then** one `DUPLICATE_GROUP` row is created containing both photo IDs

---

### UC-34 Photos with identical pixels but different extensions are NOT grouped
**Given** a JPEG and a PNG that decode to identical pixels
**When** duplicate detection runs
**Then** no `DUPLICATE_GROUP` is created for that pair (per the product spec "never cross file types")

---

### UC-35 Previously hashed photo with unchanged file size reuses cached hash
**Given** a photo was hashed in a previous run; the file has not changed (same size)
**When** duplicate detection runs again
**Then** the photo is not re-decoded; `PHOTO_HASH.hashed_at` is unchanged

---

### UC-36 Previously hashed photo with changed file size is re-hashed
**Given** a photo was hashed but the file has since changed (different size)
**When** duplicate detection runs
**Then** the photo is re-decoded and `PHOTO_HASH` is updated

---

### UC-37 Old duplicate groups from previous runs are deleted after a new run completes
**Given** a completed duplicate detection run with 3 groups
**When** a second duplicate detection run completes
**Then** the 3 groups from the first run are deleted; only the groups from the second run exist

---

### UC-38 Interrupted run leaves previous groups intact
**Given** a completed first run with 3 duplicate groups
**When** a second detection run starts but is interrupted before completing
**Then** the 3 groups from the first run are still present in the database

---

### UC-39 Detection is rejected if already running
**Given** duplicate detection is already running
**When** `start()` is called again
**Then** the second call is a no-op (warning logged); only one detection thread runs

---

## 7. Duplicate Resolution

### UC-40 Dropping a duplicate moves the file to recycle bin and removes DB rows
**Given** a duplicate group with two photos
**When** the user drops one photo from the group
**Then** the file is moved to the OS recycle bin; `DUPLICATE_GROUP_MEMBER`, `THUMBNAIL`, and `PHOTO` rows for that photo are deleted in one transaction

---

### UC-41 Group with fewer than 2 members is deleted after resolution
**Given** a duplicate group with exactly 2 photos
**When** the user drops one photo
**Then** after successful deletion, the `DUPLICATE_GROUP` row itself is also deleted

---

### UC-42 Resolution reports failure if file cannot be trashed
**Given** a file that the OS refuses to move to the recycle bin
**When** the user attempts to drop that photo from the group
**Then** the `Result.failures` list contains that photo with a reason; the `PHOTO` and `DUPLICATE_GROUP_MEMBER` rows are left untouched

---

### UC-43 File already missing from disk is treated as already-trashed
**Given** a photo whose file has been manually deleted from disk
**When** the user drops it from the duplicate group
**Then** the file-missing state is treated as a successful trash; DB rows are cleaned up normally

---

## 8. Semantic Search

### UC-44 Semantic search returns photos ranked by CLIP similarity
**Given** photos with CLIP embeddings indexed in Lucene
**When** `semanticSearch("sunset over the ocean", topK=5)` is called
**Then** up to 5 photo IDs are returned; the result list is ordered by descending cosine similarity

---

### UC-45 Similar-photos search excludes the query photo itself
**Given** a photo with a CLIP embedding
**When** `similarPhotos(photoId, topK=10)` is called
**Then** the returned list does not contain `photoId`, and has at most 10 entries

---

### UC-46 Combined person+semantic search filters by person's photos
**Given** person A has photos P1, P2, P3 and the semantic query matches P1 and some other person's photos
**When** `combinedSearch(personId=A, "beach", topK=5)` is called
**Then** only P1 (or other photos from person A) appear in the result; photos belonging to other persons are excluded

---

### UC-47 Semantic search on a library with no CLIP embeddings returns an empty list
**Given** no photos have been AI-processed yet (no CLIP embeddings)
**When** `semanticSearch("cat", topK=5)` is called
**Then** an empty list is returned without throwing

---

## 9. User Preferences

### UC-48 User preference is persisted and retrievable
**Given** no preference has been saved for key `window.x`
**When** `setUserPref(WINDOW_X, "200")` is called
**Then** `getUserPref(WINDOW_X, "100")` returns `"200"`

---

### UC-49 User preference uses default value when key is absent
**Given** no preference exists for key `window.x`
**When** `getUserPref(WINDOW_X, "100")` is called
**Then** the returned value is `"100"`

---

### UC-50 User preference update replaces previous value
**Given** `window.x` is saved as `"200"`
**When** `setUserPref(WINDOW_X, "500")` is called
**Then** `getUserPref(WINDOW_X, "0")` returns `"500"` (upsert semantics)
