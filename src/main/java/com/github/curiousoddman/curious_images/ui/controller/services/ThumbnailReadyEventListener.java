package com.github.curiousoddman.curious_images.ui.controller.services;

import com.github.curiousoddman.curious_images.event.model.ThumbnailsReadyEvent;

public interface ThumbnailReadyEventListener {
    void onThumbnailReady(ThumbnailsReadyEvent event);
}
