package com.github.curiousoddman.curious_images.event.payload;

import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaPhotoRecord;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Objects;

@Getter
@RequiredArgsConstructor
public class FaceClipProcessingFailed implements UserNotificationPayload {
    private final String      title = "Face/CLIP processing failed";
    private final MediaPhotoRecord MediaPhotoRecord;
    private final Exception   e;

    @Override
    public List<String> getDescription() {
        return List.of(
                MediaPhotoRecord.getAbsolutePath(),
                Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName())
        );
    }

    @Override
    public NotificationLevel getNotificationLevel() {
        return NotificationLevel.ERROR;
    }
}
