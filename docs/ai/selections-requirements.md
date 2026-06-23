Good. The answers narrow the scope considerably. Based on what you've described, I would treat **Selection** as a first-class domain object whose purpose is to create a curated subset of photos for a downstream task (printing, export, slideshow, publishing, etc.).

## Functional Requirements

### FR-1 Selection Management

#### FR-1.1 Create Selection

* User shall be able to create a new empty selection.
* User shall provide a name when creating a selection.
* Selection name shall be required.
* User shall be able to optionally provide a description.

#### FR-1.2 Edit Selection Metadata

* User shall be able to modify selection name.
* User shall be able to modify selection description.
* System shall maintain creation timestamp.
* System shall maintain last-modified timestamp.

#### FR-1.3 Duplicate Selection

* User shall be able to duplicate an existing selection.
* Duplicate selection shall contain the same photo membership and ordering.
* Duplicate selection shall receive a new identifier and creation timestamp.

#### FR-1.4 Delete Selection

* User shall be able to delete a selection.
* Deleting a selection shall not delete photos from the library.
* Deleting a selection shall not modify photo metadata.

---

### FR-2 Photo Membership

#### FR-2.1 Add Photos

User shall be able to add photos to a selection from:

* Library browsing
* Search results
* Albums/folders
* Tag-based filtering
* Rating-based filtering
* Rule-based queries

#### FR-2.2 Remove Photos

* User shall be able to remove photos from a selection.
* Removing a photo from a selection shall not remove it from the library.

#### FR-2.3 Membership Constraints

* A photo may belong to multiple selections.
* A photo shall not appear more than once within the same selection.

---

### FR-3 Rule-Based Selections

#### FR-3.1 Selection Rules

User shall be able to define rules such as:

* Tags
* Ratings
* Date ranges
* Other library metadata

#### FR-3.2 Dynamic Membership

* System shall update membership when rule conditions change.

#### FR-3.3 Mixed Membership

* Selection may contain both:

    * Rule-derived photos
    * Manually added photos

*(Question: should manually removed photos stay excluded even if a rule would normally include them? This is a common but important design decision.)*

---

### FR-4 Ordering

#### FR-4.1 Ordered Photos

* Selection shall maintain photo order.

#### FR-4.2 Reordering

User shall be able to:

* Move photo up/down
* Drag-and-drop reorder
* Sort by metadata

#### FR-4.3 Manual Order Preservation

* Manual ordering shall be preserved when reopening the selection.

---

### FR-5 Review and Curation

#### FR-5.1 Selection Review

User shall be able to:

* Select photos
* Remove photos from selection
* Mark photos for retention within selection

#### FR-5.2 Similar Photo Detection

* System shall identify visually similar photos.
* System shall present similar photos as groups.

#### FR-5.3 Quality Suggestions

System may identify:

* Blurry photos
* Out-of-focus photos
* Extremely dark photos
* Extremely bright photos
* Potentially low-quality photos

#### FR-5.4 Review Assistance

* System shall allow filtering by suggested quality issues.
* System shall allow reviewing similarity groups individually.

---

### FR-6 Selection-Specific Editing

Based on your clone requirement:

#### FR-6.1 Original Protection

* Editing a photo from a selection shall not modify the original photo automatically.

#### FR-6.2 Clone For Edit

* User shall be able to create an editable clone of a photo.
* Clone shall become a separate photo entity.
* Clone may replace the original photo inside the selection.

#### FR-6.3 Clone Tracking

* System shall maintain reference to source photo from which a clone was created.

---

### FR-7 Navigation

#### FR-7.1 Selection Browser

* Application shall provide a dedicated selections view.

#### FR-7.2 Open Selection

* User shall be able to open a selection.

#### FR-7.3 Single Active Selection

* Only one selection shall be active/open at a time.

#### FR-7.4 Selection Statistics

System shall display:

* Number of photos
* Creation date
* Last modified date

---

### FR-8 Persistence

#### FR-8.1 Persistent Storage

* Selections shall persist across application restarts.

#### FR-8.2 Stable References

* Membership shall reference photos using internal photo identifiers.

#### FR-8.3 Reindex Survival

* Selection membership shall survive library reindexing.

---

### FR-9 Basic History

#### FR-9.1 Change Tracking

System shall record:

* Creation date
* Last modified date

#### FR-9.2 Optional Activity Log

System may record:

* Photo added
* Photo removed
* Selection duplicated

---

## Non-Functional Requirements

### NFR-1 Performance

#### NFR-1.1 Selection Open Time

* Opening a selection of up to 150 photos should complete within 1 second on typical hardware.

#### NFR-1.2 Membership Updates

* Adding or removing photos should appear instantaneous (<100 ms perceived latency).

#### NFR-1.3 Similarity Analysis

* Similarity grouping should execute asynchronously and shall not block UI interaction.

---

### NFR-2 Scalability

System shall support:

* Libraries of at least 25,000 photos.
* Hundreds of selections.
* Selection sizes up to 150 photos.

---

### NFR-3 Reliability

* Selection data shall not be lost during normal application shutdown.
* Application shall recover persisted selections after restart.
* Corruption of a single selection shall not affect other selections.

---

### NFR-4 Usability

* Common selection operations shall be accessible through context menus and toolbar actions.
* Drag-and-drop shall be supported where appropriate.
* Selection state shall be visually distinguishable from library state.

---

### NFR-5 Extensibility

Selection model shall allow future support for:

* Workflow states
* Export workflows
* Print workflows
* Slideshow workflows
* Publishing workflows
* Additional metadata fields

---

## Domain Model

At minimum I would model:

```text
Selection
 ├─ id
 ├─ name
 ├─ description
 ├─ createdAt
 ├─ modifiedAt
 ├─ photos[]
 ├─ rules[]
 └─ metadata

SelectionPhoto
 ├─ photoId
 ├─ position
 ├─ addedAt
 └─ source (manual/rule)

Photo
 ├─ id
 ├─ fileReference
 └─ metadata

PhotoClone
 ├─ id
 ├─ sourcePhotoId
 └─ editedContent
```

One unresolved design issue remains:

**How should rule-based and manual membership interact?**

For example:

1. Rule says "all 5-star photos".
2. Photo A is 5-star and appears.
3. User manually removes Photo A from the selection.

Should:

* A) Photo A stay excluded ("manual override"), or
* B) Photo A reappear because it still matches the rule?

This decision affects the data model significantly, so I would resolve it before implementation.
