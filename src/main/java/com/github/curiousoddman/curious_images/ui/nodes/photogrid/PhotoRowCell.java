package com.github.curiousoddman.curious_images.ui.nodes.photogrid;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.model.LoadedFxml;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.FxmlView;
import com.github.curiousoddman.curious_images.ui.controller.custom.PhotoGridRowController;
import javafx.scene.Parent;
import javafx.scene.control.ListCell;

import java.util.List;

import static com.sun.javafx.util.Utils.runOnFxThread;

/**
 * A {@code ListView<PhotoGridRow>} cell — the core of the virtualized photo grid.
 * <p>
 * {@code ListView} only ever instantiates enough of these to cover the visible viewport plus a
 * small buffer, and recycles them as the user scrolls (calling {@link #updateItem} with a new
 * {@link PhotoGridRow} on the same instance) instead of creating a new one per row. That bounds
 * the number of live nodes regardless of how many thousands of photos are in a selection — this
 * is what replaces the old ever-growing {@code FlowPane}.
 */
public class PhotoRowCell extends ListCell<PhotoGridRow> {

    private final PhotoGridRowController rowController;
    private final Parent                 rowNode;
    private final PhotoGridCallbacks     callbacks;

    private List<PhotoRecord> currentPhotos;

    public PhotoRowCell(PhotoGridCallbacks callbacks, FxmlLoader fxmlLoader) {
        this.callbacks = callbacks;
        LoadedFxml<PhotoGridRowController> loaded = fxmlLoader.load(FxmlView.PHOTO_GRID_ROW, null);
        this.rowNode = loaded.parent();
        this.rowController = loaded.controller();
        rowController.bindOnce(
                callbacks.thumbnailSizeProperty(),
                callbacks::onPhotoClicked,
                callbacks::tooltipTextFor
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