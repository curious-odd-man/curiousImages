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
    ARTISTS_SPLIT_WIDTH("ui.split.width"),        // divider position (px)

    // ── AI performance (live-appliable; see AiSettingsService) ─────────────────
    AI_EXECUTION_PROVIDER("ai.execution-provider"),
    AI_INTRA_OP_THREADS("ai.intra-op-threads"),
    AI_BATCH_SIZE("ai.batch-size"),
    AI_DUPLICATE_DETECTION_THREAD_COUNT("ai.duplicate-detection.thread-count"),
    AI_FACE_ONLY("ai.features.face-only"),

    // ── Album-generation tuning (live-appliable) ────────────────────────────
    AI_EVENT_GAP_HOURS("ai.album.event-gap-hours"),
    AI_MIN_EVENT_SIZE("ai.album.min-event-size"),
    AI_MIN_LOCATION_SIZE("ai.album.min-location-size"),
    AI_MIN_CLUSTER_SIZE("ai.album.min-cluster-size"),
    AI_MIN_CLUSTER_SIMILARITY("ai.album.min-cluster-similarity");

    private final String key;
}