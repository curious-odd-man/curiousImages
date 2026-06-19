package com.github.curiousoddman.curious_images.domain.user.prefs;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum UserPrefKey {

    // ── Window ──────────────────────────────────────────────────────────────
    WINDOW_X("window.x"),
    WINDOW_Y("window.y"),
    WINDOW_WIDTH("window.width"),
    WINDOW_HEIGHT("window.height"),
    WINDOW_MAXIMIZED("window.maximized"),
    LYRICS_FONT_SIZE("font.size"),

    // ── UI layout ───────────────────────────────────────────────────────────
    ARTISTS_SPLIT_WIDTH("ui.split.width");        // divider position (px)

    private final String key;
}