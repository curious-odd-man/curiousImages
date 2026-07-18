package com.github.curiousoddman.curious_images.model;

import javafx.scene.layout.Pane;

public record UiElement<T>(Pane pane, T controller) {

}
