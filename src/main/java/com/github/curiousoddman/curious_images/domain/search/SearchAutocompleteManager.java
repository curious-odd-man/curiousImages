package com.github.curiousoddman.curious_images.domain.search;

import com.github.curiousoddman.curious_images.persistence.PersonRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoTagRepository;
import javafx.animation.PauseTransition;
import javafx.geometry.Bounds;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.stage.Popup;
import javafx.util.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static com.sun.javafx.util.Utils.runOnFxThread;

/**
 * Drives the {@code @person} / {@code #tag} suggestion popup for the library search field.
 * <p>
 * Not built on ControlsFX (version-incompatible with the rest of the project) — this is a
 * hand-rolled {@link Popup} + {@link ListView} instead, wired onto a plain {@code TextField}.
 * <p>
 * <b>Commit-driven, not just quote-for-spaces.</b> A suggestion only becomes a real filter when
 * the user explicitly commits it — by clicking it, or by Ctrl+Enter for the first/highlighted
 * suggestion. Committing rewrites the in-progress {@code @prefix} / {@code #prefix} token into a
 * quoted {@code @"Full Name"} / {@code #"tag name"} token, which is the only thing
 * {@code SearchQueryParser} recognizes as a filter. Walking away from a suggestion — most notably
 * by typing a plain Space — leaves the typed {@code @prefix} as unquoted literal text instead of
 * silently promoting it to a filter that was never validated against the database.
 * <p>
 * Key handling inside an open popup:
 * <ul>
 *   <li><b>Up / Down</b> — move the highlighted suggestion.</li>
 *   <li><b>Ctrl+Enter</b> — commit the highlighted suggestion.</li>
 *   <li><b>Ctrl+Space</b> — insert a literal space into the token being typed (so names/tags
 *       with spaces, e.g. "Anna Smith", can be searched for) without closing the popup.</li>
 *   <li><b>plain Space</b> — closes the popup; the token typed so far is left as unquoted text.</li>
 *   <li><b>Escape</b> — closes the popup without changing the text.</li>
 * </ul>
 * Clicking directly on a suggestion always commits it, regardless of the above.
 */
@Component
@RequiredArgsConstructor
public class SearchAutocompleteManager {

    private static final int SUGGESTION_LIMIT = 8;
    private static final String NO_MATCHES = "\u0000NO_MATCHES\u0000"; // sentinel, never a real name/tag

    // Ctrl+Space inserts this stand-in instead of a real space, so a committed multi-word name
    // stays part of the same token. Deliberately NOT matched by Java's \s or
    // Character.isWhitespace (verified: U+00A0 is excluded from both), so plain "\S*" below
    // already includes it without special-casing.
    private static final char SPACE_MARKER = '\u00A0';
    // The @ or # token containing the caret: marker followed by a run of non-whitespace chars.
    private static final Pattern TOKEN_AT_CARET = Pattern.compile("([@#])(\\S*)$");

    private final PersonRepository   personRepository;
    private final PhotoTagRepository photoTagRepository;

    private final Popup                popup          = new Popup();
    private final ListView<String>     suggestionList = new ListView<>();
    private final PauseTransition      debounce       = new PauseTransition(Duration.millis(150));

    private TextField searchField;
    private char currentMarker;
    private int  tokenStart = -1; // index of the @ or # in the field's text, or -1 if popup isn't tracking a token

    public void initialize(TextField searchField) {
        this.searchField = searchField;

        suggestionList.setPrefHeight(220);
        suggestionList.setPrefWidth(260);
        suggestionList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setDisable(false);
                } else if (NO_MATCHES.equals(item)) {
                    setText("No matches");
                    setDisable(true);
                } else {
                    setText(item);
                    setDisable(false);
                }
            }
        });

        popup.getContent().add(suggestionList);
        popup.setAutoHide(true);
        popup.setOnAutoHide(e -> tokenStart = -1);

        debounce.setOnFinished(e -> fetchSuggestions());

        searchField.textProperty()
                   .addListener((obs, oldText, newText) -> onTextChanged());
        searchField.caretPositionProperty()
                   .addListener((obs, oldPos, newPos) -> onTextChanged());

        // Filter, not just handle, so we can intercept plain Space / Ctrl+Space / Ctrl+Enter
        // before the TextField's default behavior (inserting a character, or firing the
        // search button's default action on Enter) runs.
        searchField.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);

        suggestionList.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                String selected = suggestionList.getSelectionModel()
                                                .getSelectedItem();
                if (selected != null && !NO_MATCHES.equals(selected)) {
                    commit(selected);
                }
            }
        });
    }

    private void onTextChanged() {
        String text  = searchField.getText();
        int    caret = searchField.getCaretPosition();
        if (text == null || caret > text.length()) {
            hidePopup();
            return;
        }

        Matcher matcher = TOKEN_AT_CARET.matcher(text.substring(0, caret));
        if (!matcher.find()) {
            hidePopup();
            return;
        }

        currentMarker = matcher.group(1)
                               .charAt(0);
        tokenStart = matcher.start();
        debounce.playFromStart();
    }

    private void fetchSuggestions() {
        if (tokenStart < 0) {
            return;
        }
        String text  = searchField.getText();
        int    caret = searchField.getCaretPosition();
        if (text == null || caret > text.length() || tokenStart >= caret) {
            return;
        }

        // SPACE_MARKER stand-ins (from Ctrl+Space) become real spaces for the DB query.
        String prefix = text.substring(tokenStart + 1, caret)
                            .replace(SPACE_MARKER, ' ');
        char marker = currentMarker;
        int  requestTokenStart = tokenStart; // guard against a stale response after the token moved

        runOnDaemonThread("SearchAutocomplete", () -> {
            List<String> suggestions = marker == '@'
                    ? personRepository.findNameSuggestions(prefix, SUGGESTION_LIMIT)
                    : photoTagRepository.findTagSuggestions(prefix, SUGGESTION_LIMIT);
            runOnFxThread(() -> {
                if (tokenStart == requestTokenStart) {
                    showSuggestions(suggestions);
                }
            });
        });
    }

    private void showSuggestions(List<String> suggestions) {
        List<String> items = suggestions.isEmpty() ? List.of(NO_MATCHES) : suggestions;
        suggestionList.getItems()
                      .setAll(items);
        suggestionList.getSelectionModel()
                      .selectFirst();

        if (!popup.isShowing()) {
            Bounds bounds = searchField.localToScreen(searchField.getBoundsInLocal());
            popup.show(searchField, bounds.getMinX(), bounds.getMaxY());
        }
    }

    private void onKeyPressed(KeyEvent event) {
        if (!popup.isShowing() || tokenStart < 0) {
            return;
        }

        boolean ctrl = event.isControlDown() || event.isShortcutDown();

        switch (event.getCode()) {
            case DOWN -> {
                suggestionList.getSelectionModel()
                              .selectNext();
                event.consume();
            }
            case UP -> {
                suggestionList.getSelectionModel()
                              .selectPrevious();
                event.consume();
            }
            case ENTER -> {
                if (ctrl) {
                    String selected = suggestionList.getSelectionModel()
                                                    .getSelectedItem();
                    if (selected != null && !NO_MATCHES.equals(selected)) {
                        commit(selected);
                    }
                    event.consume();
                } else {
                    // Plain Enter: abandon the in-progress token as literal text, same as Space,
                    // and let the field's normal action (triggering search) proceed untouched.
                    hidePopup();
                }
            }
            case SPACE -> {
                if (ctrl) {
                    insertLiteralSpaceMarker();
                    event.consume();
                } else {
                    // Abandon: token stays as unquoted text, popup closes, space types normally.
                    hidePopup();
                }
            }
            case ESCAPE -> {
                hidePopup();
                event.consume();
            }
            case TAB -> {
                // Tab should move focus normally; don't trap it, just close the popup first.
                hidePopup();
            }
            default -> {
            }
        }
    }

    /** Inserts the Ctrl+Space stand-in character at the caret, keeping the token "open". */
    private void insertLiteralSpaceMarker() {
        int    caret = searchField.getCaretPosition();
        String text  = searchField.getText();
        searchField.setText(text.substring(0, caret) + SPACE_MARKER + text.substring(caret));
        searchField.positionCaret(caret + 1);
    }

    /**
     * Replaces the whole in-progress token — from the {@code @}/{@code #} marker up to the
     * caret — with a quoted, committed filter, regardless of where within the token the caret
     * was when the user committed. Per your rule 4: editing mid-token always replaces the whole
     * token, never just a fragment of it.
     */
    private void commit(String value) {
        String text  = searchField.getText();
        int    caret = searchField.getCaretPosition();

        // Find the end of the current token even if the caret isn't at the very end of it —
        // i.e. re-run the same "token at caret" match but anchored, then extend to any trailing
        // run of prefix/space-marker characters past the caret too.
        int tokenEnd = caret;
        while (tokenEnd < text.length() &&
               (!Character.isWhitespace(text.charAt(tokenEnd)) || text.charAt(tokenEnd) == SPACE_MARKER)) {
            tokenEnd++;
        }

        String replacement = currentMarker + "\"" + value + "\" ";
        String updated = text.substring(0, tokenStart) + replacement + text.substring(tokenEnd);
        searchField.setText(updated);
        searchField.requestFocus();
        searchField.positionCaret(tokenStart + replacement.length());
        hidePopup();
    }

    private void hidePopup() {
        tokenStart = -1;
        if (popup.isShowing()) {
            popup.hide();
        }
    }
}
