package com.github.curiousoddman.curious_images.model;

import javafx.scene.layout.Pane;

public record LoadedFxml<T>(Pane parent, T controller) {
}
