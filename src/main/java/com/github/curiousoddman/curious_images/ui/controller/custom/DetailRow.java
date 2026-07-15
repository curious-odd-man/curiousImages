package com.github.curiousoddman.curious_images.ui.controller.custom;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.kordamp.ikonli.Ikon;

@Data
@RequiredArgsConstructor
public final class DetailRow {
    private final Ikon   icon;
    private final String label;
    private final String value;

    private boolean different;
}
