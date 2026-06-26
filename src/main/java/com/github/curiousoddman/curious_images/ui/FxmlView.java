package com.github.curiousoddman.curious_images.ui;

import com.github.curiousoddman.curious_images.ui.controller.screen.DuplicatesController;
import com.github.curiousoddman.curious_images.ui.controller.screen.LibraryController;
import com.github.curiousoddman.curious_images.ui.controller.screen.PersonDetailController;
import com.github.curiousoddman.curious_images.ui.controller.screen.RescanLibraryController;
import com.github.curiousoddman.curious_images.ui.controller.screen.SlideshowController;

public record FxmlView<T>(String fxmlPath, Class<T> controllerClass) {
    public static final FxmlView<LibraryController>       LIBRARY       = new FxmlView<>("/fxml/library.fxml", LibraryController.class);
    public static final FxmlView<RescanLibraryController> RESCAN_MODAL  = new FxmlView<>("/fxml/rescan-modal.fxml", RescanLibraryController.class);
    public static final FxmlView<SlideshowController>     SLIDESHOW     = new FxmlView<>("/fxml/slideshow.fxml", SlideshowController.class);
    public static final FxmlView<DuplicatesController>    DUPLICATES    = new FxmlView<>("/fxml/duplicates.fxml", DuplicatesController.class);
    public static final FxmlView<PersonDetailController>  PERSON_DETAIL = new FxmlView<>("/fxml/person_detail.fxml", PersonDetailController.class);
}
