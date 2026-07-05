package com.github.curiousoddman.curious_images.ui;

import com.github.curiousoddman.curious_images.ui.controller.screen.AddFilesController;
import com.github.curiousoddman.curious_images.ui.controller.screen.DuplicatesController;
import com.github.curiousoddman.curious_images.ui.controller.screen.LibraryController;
import com.github.curiousoddman.curious_images.ui.controller.screen.PersonDetailController;
import com.github.curiousoddman.curious_images.ui.controller.screen.ReScanLibraryController;
import com.github.curiousoddman.curious_images.ui.controller.screen.RescanRootsController;
import com.github.curiousoddman.curious_images.ui.controller.screen.SlideshowController;

public record FxmlView<T>(String fxmlPath, Class<T> controllerClass) {
    public static final FxmlView<LibraryController>       LIBRARY       = new FxmlView<>("/fxml/library.fxml", LibraryController.class);
    public static final FxmlView<ReScanLibraryController> RE_SCAN_MODAL = new FxmlView<>("/fxml/re_scan-modal.fxml", ReScanLibraryController.class);
    public static final FxmlView<SlideshowController>     SLIDESHOW     = new FxmlView<>("/fxml/slideshow.fxml", SlideshowController.class);
    public static final FxmlView<DuplicatesController>    DUPLICATES    = new FxmlView<>("/fxml/duplicates.fxml", DuplicatesController.class);
    public static final FxmlView<PersonDetailController>  PERSON_DETAIL = new FxmlView<>("/fxml/person_detail.fxml", PersonDetailController.class);
    public static final FxmlView<RescanRootsController>   RESCAN_ROOTS  = new FxmlView<>("/fxml/rescan_roots.fxml", RescanRootsController.class);
    public static final FxmlView<AddFilesController>      ADD_FILES     = new FxmlView<>("/fxml/add_files.fxml", AddFilesController.class);
}
