package com.github.curiousoddman.curious_images.ui.controller.services;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoPreviewRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ThumbnailRecord;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.ThumbnailUtils;
import com.github.curiousoddman.curious_images.event.model.ThumbnailsReadyEvent;
import com.github.curiousoddman.curious_images.persistence.AlbumPhotoRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoPreviewRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoRepository;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.controller.custom.PhotoCellController;
import com.github.curiousoddman.curious_images.ui.controller.custom.PhotoGridRowController;
import com.github.curiousoddman.curious_images.ui.controller.screen.PersonDetailController;
import com.github.curiousoddman.curious_images.ui.nodes.photogrid.PhotoGridCallbacks;
import com.github.curiousoddman.curious_images.ui.nodes.photogrid.PhotoGridRow;
import com.github.curiousoddman.curious_images.ui.nodes.photogrid.PhotoRowCell;
import com.github.curiousoddman.curious_images.util.async.DelayedAction;
import com.github.curiousoddman.curious_images.util.async.jobs.JobManager;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.github.curiousoddman.curious_images.ui.controller.screen.DuplicatesController.getPhotoDetailsText;
import static com.github.curiousoddman.curious_images.ui.controller.screen.PersonDetailController.getRowInfo;
import static com.github.curiousoddman.curious_images.ui.util.UiUtils.openSlideshow;
import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static com.sun.javafx.util.Utils.runOnFxThread;

@Slf4j
@Component
@RequiredArgsConstructor
public class PhotoGridManager implements PhotoGridCallbacks {
    /**
     * Thumbnail files are decoded at most at this size regardless of the slider's actual value —
     * matches {@code thumbnailSizeSlider}'s max (see library.fxml) so JavaFX never decodes more
     * pixels than the grid could ever display, however large the on-disk cached thumbnail is.
     */
    public static final int MAX_THUMBNAIL_DECODE_SIZE = 320;

    // Heuristics used to turn "viewport width" + "thumbnail size" into "columns per row" / "row
    // height" for the virtualized grid — see recomputeGridMetrics(). Approximate on purpose; a
    // few pixels off just means a slightly less tight fit, not a layout bug.
    private static final double CELL_HPADDING             = 12.0; // photo_cell.fxml left+right padding
    private static final double ROW_HGAP                  = 8.0;  // photo_grid_row.fxml HBox spacing
    private static final double GRID_HPADDING             = 20.0; // library.fxml ListView left+right padding
    private static final double SCROLLBAR_ALLOWANCE       = 16.0; // room for the ListView's own scrollbar
    private static final double LABEL_HEIGHT_ESTIMATE     = 40.0; // wrapped filename label, ~2 lines
    private static final double ROW_VGAP                  = 8.0;  // vertical gap between grid rows
    private static final int    GRID_METRICS_DEBOUNCE_MS  = 150;
    private static final int    THUMBNAIL_GEN_DEBOUNCE_MS = 150;

    /**
     * Photo-id → cell controller for every currently *visible* cell — populated/cleared by
     * {@link #onRowShown}/{@link #onRowHidden} as rows scroll in and out of view. Used by
     * {@link #onThumbnailsReady} to swap a placeholder for the real thumbnail on any cell that's
     * still on-screen. A miss just means the photo has scrolled away since the request was made —
     * not an error — it'll get looked up fresh from the DB the next time its row is shown again.
     */
    private final Map<Long, PhotoCellController> visiblePhotoCells = new HashMap<>();
    private final FxmlLoader                     fxmlLoader;
    private final PhotoRepository                photoRepository;
    private final AlbumPhotoRepository           albumPhotoRepository;


    /**
     * Current column count for the grid, so {@link #recomputeGridMetrics} can tell whether a
     * resize/slider change actually needs the rows regrouping, or whether only the (continuously
     * slider-bound) cell sizes changed.
     */
    private int lastColumns = -1;

    /**
     * Photo IDs collected from visible rows that turned out to have no cached thumbnail yet,
     * batched up and flushed as a single {@code submitThumbnailGenerationJob} call after a short
     * debounce — see {@link #queueThumbnailGeneration}. Avoids firing one (mutually-superseding)
     * job per row during a fast scroll.
     */
    private final Set<Long>     pendingThumbnailGenIds = new HashSet<>();
    private final DelayedAction thumbnailGenDebounce   = new DelayedAction(THUMBNAIL_GEN_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    private final DelayedAction gridMetricsDebounce    = new DelayedAction(GRID_METRICS_DEBOUNCE_MS, TimeUnit.MILLISECONDS);


    private final ThumbnailRepository    thumbnailRepository;
    private final JobManager             jobManager;
    private final PhotoPreviewRepository photoPreviewRepository;

    private Label                  photoCountLabel;
    private ListView<PhotoGridRow> photoGridListView;
    private Slider                 thumbnailSizeSlider;
    private SelectionModel         selectionModel;

    public void initialize(Label photoCountLabel, ListView<PhotoGridRow> photoGridListView, Slider thumbnailSizeSlider, SelectionModel selection) {
        this.photoCountLabel = photoCountLabel;
        this.photoGridListView = photoGridListView;
        this.thumbnailSizeSlider = thumbnailSizeSlider;
        this.selectionModel = selection;

        // Virtualized photo grid: ListView only ever instantiates enough PhotoRowCells to cover
        // the viewport plus a small buffer, recycling them on scroll — this replaces the old
        // ever-growing FlowPane with a node count bounded by
        // viewport size, not selection size.
        // FIXME: duplicate
        photoGridListView.setCellFactory(lv -> new PhotoRowCell(this, fxmlLoader));
        photoGridListView.setFocusTraversable(false);

        // Column count depends on viewport width; row height depends on thumbnail size. Both are
        // recomputed (debounced) on resize/slider-drag — see recomputeGridMetrics(). The visible
        // cells' own image/placeholder sizes are bound directly to the slider (not debounced), so
        // dragging the slider still resizes on-screen thumbnails smoothly in real time; only the
        // heavier "which photos belong in which row" regroup lags slightly behind.
        photoGridListView.widthProperty()
                         .addListener((obs, oldValue, newValue) ->
                                 gridMetricsDebounce.reSchedule(() -> recomputeGridMetrics(false)));
        thumbnailSizeSlider.valueProperty()
                           .addListener((obs, oldValue, newValue) ->
                                   gridMetricsDebounce.reSchedule(() -> recomputeGridMetrics(false)));

    }

    /**
     * Applies a freshly-loaded, fully-ordered photo set to the grid. Unlike the old paged
     * {@code FlowPane} approach, the entire set is handed to the (virtualized) {@code ListView}
     * right away — only {@link #recomputeGridMetrics} decides how it's chunked into rows, and only
     * rows that actually scroll into view ever get a live cell or a thumbnail/preview lookup.
     */
    public void populate(List<PhotoRecord> photos) {
        visiblePhotoCells.clear();
        pendingThumbnailGenIds.clear();
        selectionModel.setPhotos(photos);
        photoCountLabel.setText(photos.size() + " photo" + (photos.size() == 1 ? "" : "s"));
        recomputeGridMetrics(true); // force a regroup even if the column count is unchanged
    }

    public void clear() {
        selectionModel.nextGeneration();
        visiblePhotoCells.clear();
        pendingThumbnailGenIds.clear();
        selectionModel.clear();
        photoGridListView.getItems()
                         .clear();
        photoCountLabel.setText("");
    }

    // FIXME: duplicate
    public void recomputeGridMetrics(boolean force) {
        double viewportWidth = photoGridListView.getWidth();
        if (viewportWidth <= 0) {
            return; // not laid out yet; the width listener will fire again once it is
        }
        double thumbSize = thumbnailSizeSlider.getValue();
        double cellWidth = thumbSize + CELL_HPADDING + ROW_HGAP;
        int    columns   = Math.max(1, (int) Math.floor((viewportWidth - GRID_HPADDING - SCROLLBAR_ALLOWANCE) / cellWidth));
        double rowHeight = thumbSize + LABEL_HEIGHT_ESTIMATE + ROW_VGAP;

        photoGridListView.setFixedCellSize(rowHeight);

        if (force || columns != lastColumns) {
            lastColumns = columns;
            regroupIntoRows(columns);
        }
    }

    // FIXME: duplicate
    public void regroupIntoRows(int columns) {
        List<PhotoGridRow> rows = new ArrayList<>();
        for (int i = 0; i < selectionModel.size(); i += columns) {
            rows.add(new PhotoGridRow(selectionModel.photosSlice(i, i + columns)));
        }
        photoGridListView.getItems()
                         .setAll(rows);
    }

    @EventListener
    public void onThumbnailsReady(ThumbnailsReadyEvent event) {
        ThumbnailUtils.updateThumbnailImage(thumbnailRepository, visiblePhotoCells, event);
    }

    @Override
    public ObservableValue<Number> thumbnailSizeProperty() {
        return thumbnailSizeSlider.valueProperty();
    }

    @Override
    public void onPhotoClicked(PhotoRecord photo) {
        Integer idx = selectionModel.indexById(photo.getId());
        if (idx != null) {
            openSlideshow(selectionModel.photos(), idx, photoGridListView.getScene(), fxmlLoader);
        }
    }

    @Override
    public String tooltipTextFor(PhotoRecord photo) {
        return getPhotoDetailsText(photo);
    }

    @Override
    public void onRowShown(PhotoGridRowController row, List<PhotoRecord> photos) {
        PersonDetailController.RowInfo rowInfo = getRowInfo(row, photos, visiblePhotoCells, selectionModel.generation());


        runOnDaemonThread("Thumbnails", () -> {
            Map<Long, ThumbnailRecord>    thumbs   = thumbnailRepository.findByPhotoIds(rowInfo.ids());
            Map<Long, PhotoPreviewRecord> previews = photoPreviewRepository.findByPhotoIds(rowInfo.ids());
            runOnFxThread(() -> {
                if (rowInfo.myGeneration() != selectionModel.generation() || rowInfo.myShowToken() != row.getShowToken()) {
                    return; // selection changed, or this row now shows different photos — discard
                }
                List<Long> missing = new ArrayList<>();
                for (PhotoRecord photo : photos) {
                    ThumbnailRecord thumbnail = thumbs.get(photo.getId());
                    if (thumbnail != null && ThumbnailUtils.hasCachedFile(thumbnail)) {
                        row.applyImage(photo, ThumbnailUtils.loadThumbnailImage(thumbnail));
                        continue;
                    }
                    PhotoPreviewRecord preview = previews.get(photo.getId());
                    if (preview != null && preview.getBytes() != null) {
                        row.applyImage(photo, loadPreviewImage(preview.getBytes()));
                    }
                    missing.add(photo.getId()); // no real thumbnail yet, even if a preview was shown
                }
                if (!missing.isEmpty()) {
                    queueThumbnailGeneration(missing);
                }
            });
        });
    }

    @Override
    public void onRowHidden(PhotoGridRowController row, List<PhotoRecord> previousPhotos) {
        for (PhotoRecord photo : previousPhotos) {
            visiblePhotoCells.remove(photo.getId());
        }
    }

    public void loadPhotosForFolder(long folderId) {
        long myGeneration = selectionModel.nextGeneration();
        runOnDaemonThread("LoadFolder", () -> {
            List<PhotoRecord> photos = photoRepository.findByFolderId(folderId);
            loadSelectionResult(myGeneration, photos);
        });
    }

    public void loadPhotosForTimeline(int year, int month, Integer day) {
        long myGeneration = selectionModel.nextGeneration();
        runOnDaemonThread("LoadTimeline", () -> {
            List<PhotoRecord> photos = photoRepository.findByCaptureDate(year, month, day);
            loadSelectionResult(myGeneration, photos);
        });
    }

    public void loadPhotosUndated() {
        long myGeneration = selectionModel.nextGeneration();
        runOnDaemonThread("LoadUndated", () -> {
            List<PhotoRecord> photos = photoRepository.findByNullCaptureDate();
            loadSelectionResult(myGeneration, photos);
        });
    }

    public void loadPhotosForAlbum(long albumId) {
        long myGeneration = selectionModel.nextGeneration();
        runOnDaemonThread("LoadAlbum", () -> {
            List<Long> photoIds = albumPhotoRepository.findPhotoIdsByAlbumId(albumId);
            List<PhotoRecord> photos = photoIds.stream()
                                               .map(id -> photoRepository.findById(id)
                                                                         .orElse(null))
                                               .filter(Objects::nonNull)
                                               .toList();
            loadSelectionResult(myGeneration, photos);
        });
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

    private Image loadPreviewImage(byte[] previewBytes) {
        return new Image(new ByteArrayInputStream(previewBytes), MAX_THUMBNAIL_DECODE_SIZE, MAX_THUMBNAIL_DECODE_SIZE, true, true);
    }

    private void loadSelectionResult(long myGeneration, List<PhotoRecord> photos) {
        runOnFxThread(() -> {
            if (myGeneration == selectionModel.generation()) {
                populate(photos);
            }
        });
    }
}
