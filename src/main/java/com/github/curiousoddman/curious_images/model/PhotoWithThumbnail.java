package com.github.curiousoddman.curious_images.model;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ThumbnailRecord;

public record PhotoWithThumbnail(
        PhotoRecord photo,
        ThumbnailRecord thumbnail
) {
}
