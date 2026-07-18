package com.github.curiousoddman.curious_images.ui;

import com.github.curiousoddman.curious_images.ui.controller.custom.DuplicateCellController;
import com.github.curiousoddman.curious_images.ui.controller.custom.NotificationMenuItemController;
import com.github.curiousoddman.curious_images.ui.controller.custom.PhotoCellController;
import com.github.curiousoddman.curious_images.ui.controller.custom.PhotoGridController;
import com.github.curiousoddman.curious_images.ui.controller.custom.PhotoGridRowController;
import com.github.curiousoddman.curious_images.ui.controller.screen.AddFilesController;
import com.github.curiousoddman.curious_images.ui.controller.screen.DuplicatesController;
import com.github.curiousoddman.curious_images.ui.controller.screen.FacePickerCellController;
import com.github.curiousoddman.curious_images.ui.controller.screen.FacePickerController;
import com.github.curiousoddman.curious_images.ui.controller.custom.FolderDuplicateCellController;
import com.github.curiousoddman.curious_images.ui.controller.screen.FolderDuplicatesController;
import com.github.curiousoddman.curious_images.ui.controller.screen.LibraryController;
import com.github.curiousoddman.curious_images.ui.controller.screen.PersonDetailController;
import com.github.curiousoddman.curious_images.ui.controller.screen.ReScanLibraryController;
import com.github.curiousoddman.curious_images.ui.controller.screen.RescanRootsController;
import com.github.curiousoddman.curious_images.ui.controller.screen.SlideshowController;

public record FxmlView<T>(String fxmlPath, Class<T> controllerClass) {
    public static final FxmlView<LibraryController>              LIBRARY                 = new FxmlView<>("/fxml/library.fxml", LibraryController.class);
    public static final FxmlView<ReScanLibraryController>        RE_SCAN_MODAL           = new FxmlView<>("/fxml/re_scan-modal.fxml", ReScanLibraryController.class);
    public static final FxmlView<SlideshowController>            SLIDESHOW               = new FxmlView<>("/fxml/slideshow.fxml", SlideshowController.class);
    public static final FxmlView<DuplicatesController>           DUPLICATES              = new FxmlView<>("/fxml/duplicates.fxml", DuplicatesController.class);
    public static final FxmlView<PersonDetailController>         PERSON_DETAIL           = new FxmlView<>("/fxml/person_detail.fxml", PersonDetailController.class);
    public static final FxmlView<RescanRootsController>          RESCAN_ROOTS            = new FxmlView<>("/fxml/rescan_roots.fxml", RescanRootsController.class);
    public static final FxmlView<AddFilesController>             ADD_FILES               = new FxmlView<>("/fxml/add_files.fxml", AddFilesController.class);
    public static final FxmlView<FacePickerController>           FACE_PICKER             = new FxmlView<>("/fxml/face_picker.fxml", FacePickerController.class);
    public static final FxmlView<FacePickerCellController>       FACE_PICKER_CELL        = new FxmlView<>("/fxml/face_picker_cell.fxml", FacePickerCellController.class);
    public static final FxmlView<DuplicateCellController>        DUPLICATE_CELL          = new FxmlView<>("/fxml/duplicate_cell.fxml", DuplicateCellController.class);
    public static final FxmlView<PhotoCellController>            PHOTO_CELL              = new FxmlView<>("/fxml/photo_cell.fxml", PhotoCellController.class);
    public static final FxmlView<PhotoGridRowController>         PHOTO_GRID_ROW          = new FxmlView<>("/fxml/photo_grid_row.fxml", PhotoGridRowController.class);
    public static final FxmlView<FolderDuplicatesController>     FOLDER_DUPLICATES       = new FxmlView<>("/fxml/folder_duplicates.fxml", FolderDuplicatesController.class);
    public static final FxmlView<FolderDuplicateCellController>  FOLDER_DUPLICATE_CELL   = new FxmlView<>("/fxml/folder_duplicate_cell.fxml", FolderDuplicateCellController.class);
    public static final FxmlView<PhotoGridController>            PHOTO_GRID              = new FxmlView<>("/fxml/photo_grid.fxml", PhotoGridController.class);
    public static final FxmlView<NotificationMenuItemController> NOTIFICATIONS_MENU_ITEM = new FxmlView<>("/fxml/notification_menu_item.fxml", NotificationMenuItemController.class);
}
