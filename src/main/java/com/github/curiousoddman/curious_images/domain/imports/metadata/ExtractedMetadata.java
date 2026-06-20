package com.github.curiousoddman.curious_images.domain.imports.metadata;

import java.time.LocalDateTime;

/**
 * Result of {@link PhotoMetadataExtractor#extract(java.nio.file.Path, String)}.
 *
 * @param width             full-resolution sensor width in pixels, or {@code null} if it could
 *                          not be determined from any source
 * @param height            full-resolution sensor height in pixels, or {@code null} likewise
 * @param captureDate       best-effort capture timestamp, never {@code null} in practice (falls
 *                          all the way back to filesystem mtime) but typed nullable defensively
 * @param captureDateSource which of the priority-ordered sources {@code captureDate} came from
 */
public record ExtractedMetadata(Integer width, Integer height,
                                LocalDateTime captureDate, CaptureDateSource captureDateSource) {
}
