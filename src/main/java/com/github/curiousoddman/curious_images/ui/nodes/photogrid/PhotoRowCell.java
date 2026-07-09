package com.github.curiousoddman.curious_images.ui.nodes.photogrid;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.ui.controller.custom.PhotoGridRowController;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.ListCell;
import lombok.SneakyThrows;

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

    @SneakyThrows
    public PhotoRowCell(PhotoGridCallbacks callbacks) {
        this.callbacks = callbacks;
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/photo_grid_row.fxml"));
        this.rowNode = loader.load();
        this.rowController = loader.getController();
        rowController.bindOnce(
                callbacks.thumbnailSizeProperty(),
                callbacks::onPhotoClicked,
                callbacks::tooltipTextFor
        );
        setStyle("-fx-background-color: transparent; -fx-padding: 0;");
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