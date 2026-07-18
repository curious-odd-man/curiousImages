package com.github.curiousoddman.curious_images.event.payload;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Objects;

@Getter
@RequiredArgsConstructor
public class FaceClipProcessingFailed implements UserNotificationPayload {
    private final String      title = "Face/CLIP processing failed";
    private final PhotoRecord photoRecord;
    private final Exception   e;

    @Override
    public List<String> getDescription() {
        return List.of(
                photoRecord.getAbsolutePath(),
                Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName())
        );
    }

    @Override
    public NotificationLevel getNotificationLevel() {
        return NotificationLevel.ERROR;
    }
}
