package com.github.curiousoddman.curious_images.ui.controller.custom;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ThumbnailRecord;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.ThumbnailUtils;
import com.github.curiousoddman.curious_images.event.model.ThumbnailsReadyEvent;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.controller.services.PhotoGridModel;
import com.github.curiousoddman.curious_images.ui.nodes.photogrid.PhotoGridCallbacks;
import com.github.curiousoddman.curious_images.ui.nodes.photogrid.PhotoGridRow;
import com.github.curiousoddman.curious_images.ui.nodes.photogrid.PhotoRowCell;
import com.github.curiousoddman.curious_images.ui.util.UiUtils;
import com.github.curiousoddman.curious_images.util.async.DelayedAction;
import com.github.curiousoddman.curious_images.util.async.jobs.JobManager;
import javafx.beans.value.ObservableValue;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.github.curiousoddman.curious_images.ui.controller.screen.DuplicatesController.getPhotoDetailsText;
import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static com.sun.javafx.util.Utils.runOnFxThread;

@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class PhotoGridController implements Initializable, PhotoGridCallbacks {
    public static final int MAX_THUMBNAIL_DECODE_SIZE = 320;

    private static final double CELL_HPADDING         = 12.0; // photo_cell.fxml left+right padding
    private static final double ROW_HGAP              = 8.0;  // photo_grid_row.fxml HBox spacing
    private static final double GRID_HPADDING         = 20.0; // ListView left+right padding
    private static final double SCROLLBAR_ALLOWANCE   = 16.0; // room for the ListView's own scrollbar
    private static final double LABEL_HEIGHT_ESTIMATE = 40.0; // wrapped filename label, ~2 lines
    private static final double ROW_VGAP              = 8.0;  // vertical gap between grid rows

    private static final int GRID_METRICS_DEBOUNCE_MS  = 150;
    private static final int THUMBNAIL_GEN_DEBOUNCE_MS = 150;

    private final FxmlLoader          fxmlLoader;
    private final ThumbnailRepository thumbnailRepository;
    private final JobManager          jobManager;

    @FXML
    public ListView<PhotoGridRow> listView;
    @FXML
    public Slider                 thumbnailSizeSlider;
    @FXML
    public Label                  photoCountLabel;

    private int lastColumns = -1;

    private final PhotoGridModel                 photoGridModel         = new PhotoGridModel();
    private final Map<Long, PhotoCellController> visiblePhotoCells      = new HashMap<>();
    private final Set<Long>                      pendingThumbnailGenIds = new HashSet<>();
    private final DelayedAction                  thumbnailGenDebounce   = new DelayedAction(THUMBNAIL_GEN_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    private final DelayedAction                  gridMetricsDebounce    = new DelayedAction(GRID_METRICS_DEBOUNCE_MS, TimeUnit.MILLISECONDS);

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        listView.setCellFactory(lv -> new PhotoRowCell(this, fxmlLoader));
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
        pendingThumbnailGenIds.clear();
        photoGridModel.clear();
        photoCountLabel.setText("");
    }

    public long initiateChange() {
        return photoGridModel.nextGeneration();
    }

    public long currentChange() {
        return photoGridModel.generation();
    }

    public void populatePhotoGrid(List<PhotoRecord> photos) {
        photoGridModel.nextGeneration();
        visiblePhotoCells.clear();
        pendingThumbnailGenIds.clear();
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
    public String tooltipTextFor(PhotoRecord photo) {
        return getPhotoDetailsText(photo);
    }

    @Override
    public void onRowShown(PhotoGridRowController row, List<PhotoRecord> photos) {
        RowInfo rowInfo = getRowInfo(row, photos, visiblePhotoCells, photoGridModel.generation());

        runOnDaemonThread("Row Shown", () -> {
            Map<Long, ThumbnailRecord> thumbs = thumbnailRepository.findByPhotoIds(rowInfo.ids());
            runOnFxThread(() -> {
                if (rowInfo.myGeneration() != photoGridModel.generation() || rowInfo.myShowToken() != row.getShowToken()) {
                    return; // selection changed, or this row now shows different photos — discard
                }
                List<Long> missing = new ArrayList<>();
                for (PhotoRecord photo : photos) {
                    ThumbnailRecord thumbnail = thumbs.get(photo.getId());
                    if (thumbnail != null && hasCachedFile(thumbnail)) {
                        row.applyImage(photo, ThumbnailUtils.loadThumbnailImage(thumbnail));
                    } else {
                        missing.add(photo.getId()); // no real thumbnail yet
                    }
                }
                if (!missing.isEmpty()) {
                    queueThumbnailGeneration(missing);
                }
            });
        });
    }

    public static RowInfo getRowInfo(PhotoGridRowController row,
                                     List<PhotoRecord> photos,
                                     Map<Long, PhotoCellController> visiblePhotoCells,
                                     long selectionGeneration) {
        for (PhotoCellController cell : row.getCellControllers()) {
            PhotoRecord shown = cell.getCurrentPhoto();
            if (shown != null) {
                visiblePhotoCells.put(shown.getId(), cell);
            }
        }

        long myShowToken = row.getShowToken();
        List<Long> ids = photos.stream()
                               .map(PhotoRecord::getId)
                               .toList();
        return new RowInfo(selectionGeneration, myShowToken, ids);
    }

    public void displayError(String message) {
        photoCountLabel.setText(message);
    }

    public record RowInfo(long myGeneration, long myShowToken, List<Long> ids) {}

    @Override
    public void onRowHidden(PhotoGridRowController row, List<PhotoRecord> previousPhotos) {
        for (PhotoRecord photo : previousPhotos) {
            visiblePhotoCells.remove(photo.getId());
        }
    }

    @EventListener
    public void onThumbnailsReady(ThumbnailsReadyEvent event) {
        ThumbnailUtils.updateThumbnailImage(thumbnailRepository, visiblePhotoCells, event);
    }

    private static boolean hasCachedFile(ThumbnailRecord thumbnail) {
        return thumbnail.getCachePath() != null && new File(thumbnail.getCachePath()).isFile();
    }

    private void queueThumbnailGeneration(List<Long> ids) {
        pendingThumbnailGenIds.addAll(ids);
        thumbnailGenDebounce.reSchedule(() -> {
            if (pendingThumbnailGenIds.isEmpty()) {
                return;
            }
            List<Long> batch = List.copyOf(pendingThumbnailGenIds);
            pendingThumbnailGenIds.clear();
            jobManager.submitThumbnailGenerationJob(batch);
        });
    }

    private void openSlideshow(List<PhotoRecord> photos, int startIndex) {
        UiUtils.openSlideshow(photos, startIndex, listView.getScene(), fxmlLoader);
    }
}
