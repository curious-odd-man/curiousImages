package com.github.curiousoddman.curious_images.domain.imports;

import com.github.curiousoddman.curious_images.util.TimeProvider;

import java.time.LocalDateTime;

/**
 * A {@link TimeProvider} that advances by one second on every call instead of reading the system
 * clock, so tests asserting "this timestamp moved forward" (e.g. {@code last_seen_at} across two
 * scans) never depend on wall-clock resolution or scan duration.
 */
class FakeTimeProvider extends TimeProvider {
    private LocalDateTime current = LocalDateTime.of(2024, 1, 1, 0, 0, 0);

    @Override
    public LocalDateTime now() {
        current = current.plusSeconds(1);
        return current;
    }
}
