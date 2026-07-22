package com.github.curiousoddman.curious_images.domain.search;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the library search field's raw text into {@code @person} filters, {@code #tag}
 * filters, and remaining free text for semantic search.
 * <p>
 * Grammar (informal, commit-driven): only a <b>quoted</b> token — {@code @"name"} or
 * {@code #"tag"} — is treated as a filter. This is deliberate, not just a way to allow spaces:
 * {@link SearchAutocompleteManager} is the only thing that ever writes a quoted token, and only
 * when the user explicitly commits a suggestion from the dropdown (click, or Ctrl+Enter). An
 * unquoted {@code @word} or {@code #word} — e.g. one the user typed and then abandoned by
 * hitting plain Space instead of committing — was never validated against the database, so it's
 * treated as ordinary free text rather than a filter that would silently match nothing. (A
 * hand-typed {@code @"Anna"} with quotes still works as a filter — that's fine: typing the exact
 * syntax implies the user already knows the exact name.)
 * <p>
 * Examples:
 * <ul>
 *   <li>{@code @"Anna" sunset} → person=[Anna], freeText="sunset"</li>
 *   <li>{@code #"beach" #"sunset"} → tags=[beach, sunset], freeText=""</li>
 *   <li>{@code @ann sunset} (abandoned, unquoted) → no filters, freeText="@ann sunset"</li>
 * </ul>
 */
public final class SearchQueryParser {

    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("([@#])\"([^\"]+)\"");

    private SearchQueryParser() {
    }

    public static ParsedSearchQuery parse(String rawQuery) {
        if (rawQuery == null) {
            return new ParsedSearchQuery(List.of(), List.of(), "");
        }

        List<String>  personNames = new ArrayList<>();
        List<String>  tagNames    = new ArrayList<>();
        StringBuilder freeText    = new StringBuilder();

        Matcher matcher = TOKEN_PATTERN.matcher(rawQuery);
        int     lastEnd = 0;
        while (matcher.find()) {
            freeText.append(rawQuery, lastEnd, matcher.start());

            String marker = matcher.group(1);
            String value  = matcher.group(2)
                                   .trim();
            if (!value.isEmpty()) {
                if ("@".equals(marker)) {
                    personNames.add(value);
                } else {
                    tagNames.add(value);
                }
            }

            lastEnd = matcher.end();
        }
        freeText.append(rawQuery, lastEnd, rawQuery.length());

        return new ParsedSearchQuery(personNames, tagNames, freeText.toString().trim());
    }
}
