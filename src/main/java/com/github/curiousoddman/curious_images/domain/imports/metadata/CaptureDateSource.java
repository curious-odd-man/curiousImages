package com.github.curiousoddman.curious_images.domain.imports.metadata;

/**
 * Where a media's {@code capture_date} was sourced from, in priority order
 * (highest priority first). Persisted verbatim (by {@link #name()}) into
 * {@code PHOTO.capture_date_source}.
 */
public enum CaptureDateSource {
    EXIF_ORIGINAL,
    EXIF_DIGITIZED,
    FILESYSTEM
}
