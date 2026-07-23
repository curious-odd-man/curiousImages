package com.github.curiousoddman.curious_images.ui.controller.custom;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.ThumbnailUtils;
import com.github.curiousoddman.curious_images.event.model.ThumbnailsReadyEvent;
import com.github.curiousoddman.curious_images.model.GridCellData;
import com.github.curiousoddman.curious_images.model.bundle.GridCellResources;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.controller.services.PhotoGridModel;
import com.github.curiousoddman.curious_images.ui.controller.services.ThumbnailReadyEventListener;
import com.github.curiousoddman.curious_images.ui.nodes.photogrid.PhotoGridCallbacks;
import com.github.curiousoddman.curious_images.ui.nodes.photogrid.PhotoGridRow;
import com.github.curiousoddman.curious_images.ui.nodes.photogrid.PhotoRowCell;
import com.github.curiousoddman.curious_images.ui.util.UiUtils;
import com.github.curiousoddman.curious_images.util.async.DelayedAction;
import javafx.beans.value.ObservableValue;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;

import static com.sun.javafx.util.Utils.runOnFxThread;

@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class GridController implements Initializable, PhotoGridCallbacks, ThumbnailReadyEventListener {
    public static final int MAX_THUMBNAIL_DECODE_SIZE = 320;

    private static final double CELL_HPADDING         = 12.0; // photo_cell.fxml left+right padding
    private static final double ROW_HGAP              = 8.0;  // photo_grid_row.fxml HBox spacing
    private static final double GRID_HPADDING         = 20.0; // ListView left+right padding
    private static final double SCROLLBAR_ALLOWANCE   = 16.0; // room for the ListView's own scrollbar
    private static final double LABEL_HEIGHT_ESTIMATE = 40.0; // wrapped filename label, ~2 lines
    private static final double ROW_VGAP              = 8.0;  // vertical gap between grid rows

    private static final int GRID_METRICS_DEBOUNCE_MS = 150;

    private final FxmlLoader     fxmlLoader;
    private final ThumbnailUtils thumbnailUtils;

    @FXML
    public ListView<PhotoGridRow> listView;
    @FXML
    public Slider                 thumbnailSizeSlider;
    @FXML
    public Label                  photoCountLabel;

    private int lastColumns = -1;

    private final PhotoGridModel                photoGridModel      = new PhotoGridModel();
    private final Map<Long, GridCellController> visiblePhotoCells   = new HashMap<>();
    private final DelayedAction                 gridMetricsDebounce = new DelayedAction(GRID_METRICS_DEBOUNCE_MS, TimeUnit.MILLISECONDS);

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        listView.setCellFactory(lv -> new PhotoRowCell(this, fxmlLoader, (GridCellResources) resources));
        listView.setFocusTraversable(false);

        listView.widthProperty()
                .addListener((obs, oldValue, newValue) ->
                        gridMetricsDebounce.reSchedule(() -> recomputeGridMetrics(false)));
        thumbnailSizeSlider.valueProperty()
                           .addListener((obs, oldValue, newValue) ->
                                   gridMetricsDebounce.reSchedule(() -> recomputeGridMetrics(false)));
    }

    public void clear() {
        photoGridModel.nextGeneration();
        visiblePhotoCells.clear();
        photoGridModel.clear();
        photoCountLabel.setText("");
    }

    public long initiateChange() {
        return photoGridModel.nextGeneration();
    }

    public long currentChange() {
        return photoGridModel.generation();
    }

    public void populatePhotoGrid(List<GridCellData> photos) {
        photoGridModel.nextGeneration();
        visiblePhotoCells.clear();
        photoGridModel.setPhotos(photos);
        photoCountLabel.setText(photos.size() + " photo" + (photos.size() == 1 ? "" : "s"));
        recomputeGridMetrics(true); // force a regroup even if the column count is unchanged
    }

    private void recomputeGridMetrics(boolean force) {
        double viewportWidth = listView.getWidth();
        if (viewportWidth <= 0) {
            return; // not laid out yet; the width listener will fire again once it is
        }
        double thumbSize = thumbnailSizeSlider.getValue();
        double cellWidth = thumbSize + CELL_HPADDING + ROW_HGAP;
        int    columns   = Math.max(1, (int) Math.floor((viewportWidth - GRID_HPADDING - SCROLLBAR_ALLOWANCE) / cellWidth));
        double rowHeight = thumbSize + LABEL_HEIGHT_ESTIMATE + ROW_VGAP;

        listView.setFixedCellSize(rowHeight);

        if (force || columns != lastColumns) {
            lastColumns = columns;
            regroupIntoRows(columns);
        }
    }

    private void regroupIntoRows(int columns) {
        List<PhotoGridRow> rows = new ArrayList<>();
        for (int i = 0; i < photoGridModel.size(); i += columns) {
            rows.add(new PhotoGridRow(photoGridModel.photosSlice(i, i + columns)));
        }
        listView.getItems()
                .setAll(rows);
    }

    @Override
    public ObservableValue<Number> thumbnailSizeProperty() {
        return thumbnailSizeSlider.valueProperty();
    }

    @Override
    public void onPhotoClicked(PhotoRecord photo) {
        Integer idx = photoGridModel.indexById(photo.getId());
        if (idx != null) {
            openSlideshow(photoGridModel.photos(), idx);
        }
    }

    @Override
    public void onRowShown(PhotoGridRowController row, List<GridCellData> photos) {
        RowInfo rowInfo = getRowInfo(row, photos, visiblePhotoCells, photoGridModel.generation());
        runOnFxThread(() -> {
            if (rowInfo.myGeneration() != photoGridModel.generation() || rowInfo.myShowToken() != row.getShowToken()) {
                return; // selection changed, or this row now shows different photos — discard
            }
            for (GridCellData photo : photos) {
                row.applyImage(photo);
            }
        });
    }

    public static RowInfo getRowInfo(PhotoGridRowController row,
                                     List<GridCellData> photos,
                                     Map<Long, GridCellController> visiblePhotoCells,
                                     long selectionGeneration) {
        for (GridCellController cell : row.getCellControllers()) {
            GridCellData shown = cell.getGridCellData();
            if (shown != null) {
                visiblePhotoCells.put(shown.mediaId(), cell);
            }
        }

        long myShowToken = row.getShowToken();
        List<Long> ids = photos.stream()
                               .map(GridCellData::mediaId)
                               .toList();
        return new RowInfo(selectionGeneration, myShowToken, ids);
    }

    public void displayError(String message) {
        photoCountLabel.setText(message);
    }

    @Override
    public void onThumbnailReady(ThumbnailsReadyEvent event) {
        thumbnailUtils.updateThumbnailImage(visiblePhotoCells, event);
    }

    public record RowInfo(long myGeneration, long myShowToken, List<Long> ids) {}

    @Override
    public void onRowHidden(PhotoGridRowController row, List<GridCellData> previousPhotos) {
        for (GridCellData photo : previousPhotos) {
            visiblePhotoCells.remove(photo.mediaId());
        }
    }


    private void openSlideshow(List<PhotoRecord> photos, int startIndex) {
        UiUtils.openSlideshow(photos, startIndex, listView.getScene(), fxmlLoader);
    }
}
