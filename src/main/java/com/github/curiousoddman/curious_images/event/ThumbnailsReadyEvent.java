package com.github.curiousoddman.curious_images.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.Set;

/**
 * Published by {@code ThumbnailGenerationJob} once it has finished (successfully or partially,
 * e.g. interrupted by a newer selection superseding it) generating real thumbnails for a page of
 * photo IDs. Consumed by {@code LibraryController} to swap the placeholder/quick-preview image of
 * any still-visible cell for the photo IDs in {@link #getPhotoIds()} with the real thumbnail.
 */
@Getter
public class ThumbnailsReadyEvent extends ApplicationEvent {
    private final Set<Long> photoIds;

    public ThumbnailsReadyEvent(Object source, Set<Long> photoIds) {
        super(source);
        this.photoIds = photoIds;
    }
}
