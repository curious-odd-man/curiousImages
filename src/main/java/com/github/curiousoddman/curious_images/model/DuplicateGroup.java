package com.github.curiousoddman.curious_images.model;

import java.util.List;

public record DuplicateGroup(
        long groupId,
        String extension,
        String pixelHash,
        List<MediaWithThumbnail> photos
) {
}
