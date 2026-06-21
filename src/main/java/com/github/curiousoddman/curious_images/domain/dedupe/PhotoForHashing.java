package com.github.curiousoddman.curious_images.domain.dedupe;

/**
 * The subset of PHOTO columns duplicate detection actually needs. Avoids pulling every column
 * (capture_date, image dimensions, etc.) for up to 25,000 rows when only these four matter here.
 */
public record PhotoForHashing(long id, String absolutePath, String extension, long fileSize) {
}
