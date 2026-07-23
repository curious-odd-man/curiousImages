package com.github.curiousoddman.curious_images.model;

import com.github.curiousoddman.curious_images.dbobj.tables.records.ThumbnailRecord;

public record MediaWithThumbnail(
        Media media,
        ThumbnailRecord thumbnail
) {
}
