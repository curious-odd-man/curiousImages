# Prompt: Implement Photo Import Job

You are implementing the photo import subsystem for a Java desktop photo management application.

The application uses:

* Java 25
* JavaFX
* jOOQ
* Embedded H2 database
* Gradle

The existing codebase already contains:

* Spring-managed services
* Event-driven background processing
* Background process progress events
* Cancellation events
* jOOQ-generated database access
* H2 persistence
* JavaFX UI

Follow existing architectural patterns whenever possible.

---

# Goal

Implement a robust background Import Job that scans a selected root folder and imports photo metadata into the database
without modifying original files.

The import job must:

* run asynchronously
* keep JavaFX responsive
* support cancellation
* report progress
* report current file
* report elapsed time
* support rescanning later

Original files must never be modified, copied, moved, or deleted.

---

# Existing Patterns To Reuse

The current codebase already contains a prototype scanning workflow:

## FilesScanningService

Responsibilities currently include:

* receiving a scan event
* launching a background thread
* recursively traversing filesystem
* publishing progress events
* supporting interruption

Use this as the conceptual starting point but redesign where necessary.

Do not leave import logic inside controllers.

Move all business logic into application/domain services.

## Background Process Events

Reuse and extend the existing event model:

BackgroundProcessEvent

Currently supports:

* STARTED
* IN_PROGRESS
* COMPLETED
* FAILED
* INTERRUPTED

Extend progress payloads to include:

```java
String currentFile;
long filesProcessed;
long totalFiles;
Duration elapsedTime;
```

---

# High-Level Architecture

Create the following layers:

```text
ui
application
domain
persistence
filesystem
metadata
thumbnail
```

Suggested services:

```text
ImportCoordinator
ImportScanner
ImportExecutor
MetadataExtractionService
ThumbnailGenerationService
ImportPersistenceService
ImportProgressPublisher
```

Responsibilities:

### ImportCoordinator

Entry point.

Receives ImportRequestedEvent.

Starts import job.

Prevents concurrent imports.

Tracks job lifecycle.

### ImportScanner

Performs recursive discovery.

Returns stream/batch of supported files.

Must not load entire collection into memory.

### ImportExecutor

Consumes discovered files.

Processes files one-by-one.

Coordinates metadata extraction and thumbnail generation.

Supports cancellation checks.

### MetadataExtractionService

Extracts:

* path
* filename
* parent folder
* file size
* extension
* width
* height
* capture date

Capture date priority:

```text
DateTimeOriginal
CreateDate
filesystem modified timestamp
```

### ThumbnailGenerationService

Generates thumbnail cache entries.

### ImportPersistenceService

Persists entities using jOOQ.

No handwritten SQL unless absolutely necessary.

---

# Supported File Types

Import only:

```text
jpg
jpeg
png
cr2
```

Ignore:

```text
xmp
pp3
dop
thm
json
xml
```

Ignore all sidecar files.

---

# Import Pipeline

Process each file through the following pipeline:

```text
Discover file
    ↓
Validate supported type
    ↓
Extract metadata
    ↓
Create/update folder record
    ↓
Create/update photo record
    ↓
Generate thumbnail
    ↓
Create/update thumbnail record
    ↓
Publish progress
```

---

# Rescan Behavior

Rescan must be idempotent.

When a file already exists:

Compare:

```java
absolutePath
        fileSize
lastModified
```

If unchanged:

```text
skip metadata extraction
skip thumbnail generation
```

If changed:

```text
re-extract metadata
regenerate thumbnail
update database
```

If file no longer exists:

```text
mark as missing
```

Do not delete records automatically.

Add:

```java
is_missing BOOLEAN
```

to PHOTO.

---

# Database Design

Implement the following entities.

## IMPORT_ROOT

```java
id
        root_path
created_at
        last_scan_at
```

## FOLDER

```java
id
        import_root_id
absolute_path
        parent_folder_id
name
```

## PHOTO

```java
id
        folder_id
absolute_path
        filename
extension
        file_size
last_modified
        width
height
        capture_date
is_missing
        created_at
updated_at
```

Unique index:

```java
absolute_path
```

## THUMBNAIL

```java
id
        photo_id
cache_path
        width
height
        created_at
```

---

# Metadata Extraction

Use:

```text
metadata-extractor
```

library.

JPEG/PNG:

* extract EXIF
* extract dimensions

CR2:

* extract embedded metadata
* do NOT perform RAW rendering

Capture date resolution:

```java
DateTimeOriginal
    ??CreateDate
    ??filesystemModifiedTime
```

---

# Thumbnail Generation

Store thumbnails on disk.

Never store image blobs in H2.

Requirements:

```text
longest edge = 512px
preserve aspect ratio
```

Cache root:

```text
{appData}/thumbnail-cache/
```

Deterministic cache key: append full path (excluding disk name) to a cache root.

Example:

```text
thumbnail-cache/ab/cd/abcdef123456.jpg
```

If thumbnail file is missing:

```text
regenerate automatically
```

---

# CR2 Thumbnail Strategy

Do not render RAW files.

Instead:

```text
extract embedded preview image
```

Generate 512px thumbnail from embedded preview.

If preview extraction fails:

```text
store placeholder thumbnail
mark thumbnail generation warning
continue import
```

Import must never fail because a thumbnail cannot be generated.

---

# Background Job Framework

Implement:

```java
JobHandle
```

```java
UUID jobId;
Instant startedAt;
AtomicBoolean cancelled;
```

Operations:

```java
cancel()

isCancelled()
```

Create:

```java
JobManager
```

Responsibilities:

* register running jobs
* prevent duplicate imports
* track state
* expose progress

Execution:

```java
ExecutorService
```

Use a single import worker thread.

Filesystem scanning may use a producer-consumer model if desired.

---

# Progress Reporting

Publish updates periodically.

Do not publish every file.

Publish every:

```java
100 files
        or
1 second
```

whichever occurs first.

Progress payload:

```java
jobId
        currentFile
filesProcessed
        totalFiles
elapsedTime
        percentComplete
```

---

# Error Handling

Errors must be isolated per file.

Example:

```text
metadata extraction failed
thumbnail generation failed
file locked
permission denied
```

Behavior:

```text
log error
record warning
continue processing
```

Import job only fails if:

```text
root folder inaccessible
database unavailable
fatal infrastructure error
```

---

# Performance Requirements

Target:

```text
25,000 photos
```

Requirements:

* UI remains responsive
* streaming traversal
* batched persistence
* no loading all photos into memory
* thumbnails generated incrementally

Avoid:

```java
Files.walk(...).

toList()
```

Prefer:

```java
Files.walkFileTree(...)
```

with immediate processing.

---

# Deliverables

Provide:

1. Full class design
2. Sequence diagram of import flow
3. Database schema migration
4. jOOQ model generation configuration
5. Service interfaces
6. Event definitions
7. Thumbnail cache structure
8. Cancellation mechanism
9. Rescan/update algorithm
10. Step-by-step implementation plan

This design should be treated as the authoritative implementation specification for the import subsystem.
