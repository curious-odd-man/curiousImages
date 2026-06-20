# Prompt

Design and implement an MVP desktop photo management application with the following requirements.

## Technology Stack

* Java 25
* JavaFX
* jOOQ
* Embedded H2 database (file-based)
* Gradle
* Windows desktop only

The implementation should prioritize simplicity, maintainability, and responsiveness over premature optimization.

---

## Functional Requirements

### Import

The application manages a single imported root folder.

Users can:

* Select a root folder
* Run a manual import scan
* Run a manual rescan later

The application must not copy, move, modify, or delete original files.

Original files remain in their existing filesystem locations.

Supported formats:

* JPEG
* PNG
* Canon CR2

During import:

* Traverse folder tree recursively
* Extract metadata
* Generate thumbnail
* Store metadata in database
* Store thumbnail in thumbnail cache

Import must run as a background job.

The UI must remain responsive.

The import job must expose:

* progress
* current file
* elapsed time
* cancellation

---

### Metadata Extraction

Extract and persist:

* absolute path
* filename
* parent folder
* file size
* file extension
* image width
* image height
* capture date

Capture date priority:

1. DateTimeOriginal
2. CreateDate
3. filesystem modified timestamp

For RAW files:

* extract embedded preview thumbnail
* do not perform full RAW rendering

Ignore sidecar files.

---

### Thumbnail Cache

Store thumbnails on disk.

Requirements:

* longest edge = 512 pixels
* preserve aspect ratio
* deterministic cache path
* regenerate if cache entry missing

Do not store image blobs inside H2.

---

### Folder Tree View

Provide a filesystem-oriented navigation view.

Requirements:

* tree view of imported root
* mirrors actual folder hierarchy
* selecting a folder displays photos from that folder
* no albums
* no tags
* no virtual collections

---

### Grid View

Display thumbnails in a scrollable grid.

Requirements:

* lazy loading
* virtualized rendering
* support collections up to 25,000 photos
* double click opens photo viewer

Display:

* thumbnail
* filename
* capture date

---

### Timeline View

Display photos grouped by:

* Year
* Month
* Day

The timeline view is generated from capture date.

Example:

```text
2025
 └── June
      └── 15
```

Selecting a day displays associated photos.

---

### Duplicate Detection

Duplicate detection is a separate background job.

It is not part of import.

Users explicitly start duplicate detection.

The duplicate detection job:

* scans imported photos
* computes pixel hashes
* compares only within the same file type
* ignores filename differences
* ignores metadata differences

Definition of duplicate:

Two images are duplicates when decoded pixel content is identical.

Examples:

* same JPEG with different EXIF -> duplicate
* same JPEG renamed -> duplicate
* JPEG and CR3 with same image -> not duplicate

Job requirements:

* progress reporting
* cancellation support
* resumable design preferred

Store duplicate groups in database.

---

### Duplicates View

Display duplicate groups.

For each group:

* thumbnail preview
* file paths
* capture dates
* file sizes

No delete functionality in MVP.

Read-only inspection only.

---

## Database Design

Use separate tables.

Suggested entities:

* IMPORT_ROOT
* FOLDER
* PHOTO
* THUMBNAIL
* DUPLICATE_JOB
* DUPLICATE_GROUP
* DUPLICATE_GROUP_MEMBER

Generate all database access code using jOOQ.

Avoid handwritten SQL where practical.

---

## Architecture

Use a layered architecture.

Suggested modules:

```text
ui
application
domain
persistence
filesystem
metadata
thumbnail
duplicate
```

Business logic must not be placed inside JavaFX controllers.

Use dependency injection where appropriate.

---

## Performance Targets

Target collection size:

* 25,000 photos

The application must:

* remain responsive during import
* remain responsive during duplicate detection
* avoid loading full image collections into memory
* process images in streaming/batched fashion

---

## Deliverables

Provide:

1. Project structure
2. Database schema
3. jOOQ configuration
4. Domain model
5. Import pipeline design
6. Thumbnail cache design
7. Duplicate detection design
8. JavaFX screen design
9. Background job framework
10. MVP implementation plan in development phases

---

This prompt is now detailed enough that an implementation-oriented model should produce a coherent architecture rather than inventing requirements. The only remaining major decision is whether H2 is embedded file-based or server mode. For this application I'd choose embedded file-based H2.
