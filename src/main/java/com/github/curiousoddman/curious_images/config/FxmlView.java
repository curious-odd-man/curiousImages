package com.github.curiousoddman.curious_images.config;

import com.github.curiousoddman.curious_images.ui.controller.screen.DuplicatesController;
import com.github.curiousoddman.curious_images.ui.controller.screen.LibraryController;
import com.github.curiousoddman.curious_images.ui.controller.screen.RescanLibraryController;
import com.github.curiousoddman.curious_images.ui.controller.screen.SlideshowController;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class FxmlView<T> {
    public static final FxmlView<LibraryController> LIBRARY = new FxmlView<>("/fxml/library.fxml", LibraryController.class);
    public static final FxmlView<RescanLibraryController> RESCAN_MODAL = new FxmlView<>("/fxml/rescan-modal.fxml", RescanLibraryController.class);
    public static final FxmlView<SlideshowController> SLIDESHOW = new FxmlView<>("/fxml/slideshow.fxml", SlideshowController.class);
    public static final FxmlView<DuplicatesController> DUPLICATES = new FxmlView<>("/fxml/duplicates.fxml", DuplicatesController.class);

    private final String fxmlPath;
    private final Class<T> controllerClass;
}
