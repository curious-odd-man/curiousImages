package com.github.curiousoddman.curious_images.domain.search;

import java.util.List;

/**
 * Result of parsing a raw search-field string into its {@code @person} filters, {@code #tag}
 * filters, and whatever free text is left over for semantic ranking.
 * <p>
 * Multiple filters of the same kind are AND'd together by {@link SearchService} — e.g.
 * {@code @Anna @Bob} means "photos containing both Anna and Bob", not either.
 *
 * @see SearchQueryParser#parse(String)
 */
public record ParsedSearchQuery(List<String> personNames, List<String> tagNames, String freeText) {

    public boolean hasFreeText() {
        return freeText != null && !freeText.isBlank();
    }

    public boolean hasFilters() {
        return !personNames.isEmpty() || !tagNames.isEmpty();
    }

    public boolean isEmpty() {
        return !hasFilters() && !hasFreeText();
    }
}
