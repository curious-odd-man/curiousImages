package com.github.curiousoddman.curious_images.event.payload;

import java.util.List;

public interface UserNotificationPayload {

    String getTitle();

    List<String> getDescription();

    NotificationLevel getNotificationLevel();
}
