-- Embedded EXIF preview bytes (IFD1 thumbnail), extracted for free during the Phase 1 metadata
-- scan since it has already opened and parsed the file's EXIF (see
-- PhotoMetadataExtractor.extractEmbeddedPreviewBytes). Used by the UI as an instant "quick
-- preview" while the real on-demand thumbnail is generated in the background.
--
-- Kept in its own table rather than a column on PHOTO so existing PhotoRepository queries
-- (findByFolderId, etc.) stay lean — most callers don't need the preview bytes. See
-- implementation plan "Instant quick preview" section.
--
-- ON DELETE CASCADE: this is disposable, regeneratable-on-rescan data tied 1:1 to a photo, same
-- lifecycle as CLIP_EMBEDDING — no separate cleanup step needed when a PHOTO row is deleted.
CREATE TABLE photo_preview
(
    photo_id BIGINT PRIMARY KEY REFERENCES photo (id) ON DELETE CASCADE,
    bytes    VARBINARY NOT NULL
);
