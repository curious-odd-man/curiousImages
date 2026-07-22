package com.github.curiousoddman.curious_images.ui.nodes.photogrid;

import com.github.curiousoddman.curious_images.model.LoadedFxml;
import com.github.curiousoddman.curious_images.model.PhotoCellData;
import com.github.curiousoddman.curious_images.model.bundle.PhotoCellResources;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.FxmlView;
import com.github.curiousoddman.curious_images.ui.controller.custom.PhotoGridRowController;
import javafx.scene.Parent;
import javafx.scene.control.ListCell;

import java.util.List;

import static com.sun.javafx.util.Utils.runOnFxThread;

public class PhotoRowCell extends ListCell<PhotoGridRow> {

    private final PhotoGridRowController rowController;
    private final Parent                 rowNode;
    private final PhotoGridCallbacks     callbacks;

    private List<PhotoCellData> currentPhotos;

    public PhotoRowCell(PhotoGridCallbacks callbacks, FxmlLoader fxmlLoader, PhotoCellResources photoCellResources) {
        this.callbacks = callbacks;
        LoadedFxml<PhotoGridRowController> loaded = fxmlLoader.load(FxmlView.PHOTO_GRID_ROW, photoCellResources);
        this.rowNode = loaded.parent();
        this.rowController = loaded.controller();
        rowController.bindOnce(
                callbacks.thumbnailSizeProperty(),
                callbacks::onPhotoClicked
        );
        getStyleClass().add("photo-row-cell");
        setFocusTraversable(false);
    }

    @Override
    protected void updateItem(PhotoGridRow item, boolean empty) {
        runOnFxThread(() -> {
            super.updateItem(item, empty);

            if (currentPhotos != null) {
                callbacks.onRowHidden(rowController, currentPhotos);
                currentPhotos = null;
            }

            if (empty || item == null) {
                setGraphic(null);
                return;
            }

            currentPhotos = item.photos();
            rowController.showRow(currentPhotos);
            setGraphic(rowNode);
            callbacks.onRowShown(rowController, currentPhotos);
        });
    }
}
