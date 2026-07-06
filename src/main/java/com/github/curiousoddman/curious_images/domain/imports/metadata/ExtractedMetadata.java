package com.github.curiousoddman.curious_images.domain.imports.metadata;

import java.time.LocalDateTime;

/**
 * Result of {@link PhotoMetadataExtractor#extract(java.nio.file.Path, String)}.
 *
 * @param width                full-resolution sensor width in pixels, or {@code null} if it could
 *                             not be determined from any source
 * @param height               full-resolution sensor height in pixels, or {@code null} likewise
 * @param captureDate          best-effort capture timestamp, never {@code null} in practice (falls
 *                             all the way back to filesystem mtime) but typed nullable defensively
 * @param captureDateSource    which of the priority-ordered sources {@code captureDate} came from
 * @param orientationDegrees   clockwise rotation (0/90/180/270) needed to display the image
 *                             correctly, derived from the EXIF Orientation tag (1-8); {@code 0}
 *                             when the tag is absent or unrecognized. Mirrored orientations
 *                             (2,4,5,7) are not distinguished from their non-mirrored counterparts
 *                             — only the rotation component is tracked, never {@code null}
 * @param cameraMake           EXIF {@code Make}, or {@code null} if absent
 * @param cameraModel          EXIF {@code Model}, or {@code null} if absent
 * @param lensModel            EXIF {@code LensModel}, or {@code null} if absent
 * @param exifExtraJson        every other EXIF/metadata tag found, grouped by directory name and
 *                             serialized as a JSON object string; {@code null} if none were found
 * @param embeddedPreviewBytes the embedded EXIF (IFD1) preview image, if one was found — only
 *                             attempted for JPEG (see {@link PhotoMetadataExtractor#extract}); the
 *                             metadata needed to look for it has already been parsed by this call,
 *                             so extracting it costs effectively nothing extra. {@code null} for
 *                             non-JPEG files or when no usable embedded preview was found. Stored
 *                             by the caller in {@code PHOTO_PREVIEW} for instant quick-preview
 *                             display before the real thumbnail is generated on demand
 */
public record ExtractedMetadata(Integer width, Integer height,
                                LocalDateTime captureDate, CaptureDateSource captureDateSource,
                                int orientationDegrees,
                                String cameraMake, String cameraModel, String lensModel,
                                String exifExtraJson, byte[] embeddedPreviewBytes) {
}
