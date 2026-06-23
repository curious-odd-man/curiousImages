package com.github.curiousoddman.curious_images.event;

import org.springframework.context.ApplicationEvent;

/**
 * Published by the search bar in {@code library.fxml} (via {@code LibraryController}) when
 * the user presses Enter or clicks Search. Consumed by {@code LibraryController} itself to
 * populate the photo grid with semantic search results.
 */
public class SearchRequestedEvent extends ApplicationEvent {

    private final String query;

    public SearchRequestedEvent(Object source, String query) {
        super(source);
        this.query = query;
    }

    public String getQuery() {
        return query;
    }
}
