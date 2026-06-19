package com.github.curiousoddman.curious_images.config;

import com.github.curiousoddman.curious_images.ui.controller.screen.LibraryController;
import com.github.curiousoddman.curious_images.ui.controller.screen.RescanLibraryController;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class FxmlView<T> {
    public static final FxmlView<LibraryController> LIBRARY = new FxmlView<>("/fxml/library.fxml", LibraryController.class);
    public static final FxmlView<RescanLibraryController> RESCAN_MODAL = new FxmlView<>("/fxml/rescan-modal.fxml", RescanLibraryController.class);

    private final String fxmlPath;
    private final Class<T> controllerClass;
}
