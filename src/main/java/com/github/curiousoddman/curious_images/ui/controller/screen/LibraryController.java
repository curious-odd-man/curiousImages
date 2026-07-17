package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoPreviewRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ThumbnailRecord;
import com.github.curiousoddman.curious_images.domain.ai.ModelDownloadJob;
import com.github.curiousoddman.curious_images.domain.ai.ModelPaths;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.ThumbnailUtils;
import com.github.curiousoddman.curious_images.domain.search.SearchService;
import com.github.curiousoddman.curious_images.domain.user.prefs.UserPreferencesService;
import com.github.curiousoddman.curious_images.event.model.BackgroundProcessEvent;
import com.github.curiousoddman.curious_images.event.model.ThumbnailsReadyEvent;
import com.github.curiousoddman.curious_images.event.payload.BackgroundProcessPayload;
import com.github.curiousoddman.curious_images.event.types.BackgroundProcessEventType;
import com.github.curiousoddman.curious_images.model.LoadedFxml;
import com.github.curiousoddman.curious_images.model.bundle.AddFilesBundle;
import com.github.curiousoddman.curious_images.model.bundle.RescanBundle;
import com.github.curiousoddman.curious_images.persistence.AlbumPhotoRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoPreviewRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoRepository;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.FxmlView;
import com.github.curiousoddman.curious_images.ui.controller.custom.PhotoCellController;
import com.github.curiousoddman.curious_images.ui.controller.custom.PhotoGridRowController;
import com.github.curiousoddman.curious_images.ui.controller.custom.TreeManager;
import com.github.curiousoddman.curious_images.ui.nodes.LibraryTreeCell;
import com.github.curiousoddman.curious_images.ui.nodes.LibraryTreeNode;
import com.github.curiousoddman.curious_images.ui.nodes.LibraryTreeNode.NodeType;
import com.github.curiousoddman.curious_images.ui.nodes.NodePayload;
import com.github.curiousoddman.curious_images.ui.nodes.NodePayload.AlbumPayload;
import com.github.curiousoddman.curious_images.ui.nodes.NodePayload.FolderPayload;
import com.github.curiousoddman.curious_images.ui.nodes.NodePayload.PersonPayload;
import com.github.curiousoddman.curious_images.ui.nodes.NodePayload.TimelinePayload;
import com.github.curiousoddman.curious_images.ui.nodes.NodePayload.UndatedPayload;
import com.github.curiousoddman.curious_images.ui.nodes.photogrid.PhotoGridCallbacks;
import com.github.curiousoddman.curious_images.ui.nodes.photogrid.PhotoGridRow;
import com.github.curiousoddman.curious_images.ui.nodes.photogrid.PhotoRowCell;
import com.github.curiousoddman.curious_images.ui.util.AlertHelper;
import com.github.curiousoddman.curious_images.ui.util.StageUtils;
import com.github.curiousoddman.curious_images.util.CollectionUtils;
import com.github.curiousoddman.curious_images.util.async.DelayedAction;
import com.github.curiousoddman.curious_images.util.async.jobs.JobManager;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ObservableValue;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static com.github.curiousoddman.curious_images.ui.controller.screen.DuplicatesController.getPhotoDetailsText;
import static com.github.curiousoddman.curious_images.ui.controller.screen.PersonDetailController.getRowInfo;
import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static com.sun.javafx.util.Utils.runOnFxThread;
import static javafx.scene.control.ProgressIndicator.INDETERMINATE_PROGRESS;

@Lazy
@Slf4j
@Component
@RequiredArgsConstructor
public class LibraryController implements Initializable, PhotoGridCallbacks {
    private static final int SEARCH_TOP_K = 50;

    /**
     * Thumbnail files are decoded at most at this size regardless of the slider's actual value —
     * matches {@code thumbnailSizeSlider}'s max (see library.fxml) so JavaFX never decodes more
     * pixels than the grid could ever display, however large the on-disk cached thumbnail is.
     */
    public static final int MAX_THUMBNAIL_DECODE_SIZE = 320;

    // Heuristics used to turn "viewport width" + "thumbnail size" into "columns per row" / "row
    // height" for the virtualized grid — see recomputeGridMetrics(). Approximate on purpose; a
    // few pixels off just means a slightly less tight fit, not a layout bug.
    private static final double CELL_HPADDING         = 12.0; // photo_cell.fxml left+right padding
    private static final double ROW_HGAP              = 8.0;  // photo_grid_row.fxml HBox spacing
    private static final double GRID_HPADDING         = 20.0; // library.fxml ListView left+right padding
    private static final double SCROLLBAR_ALLOWANCE   = 16.0; // room for the ListView's own scrollbar
    private static final double LABEL_HEIGHT_ESTIMATE = 40.0; // wrapped filename label, ~2 lines
    private static final double ROW_VGAP              = 8.0;  // vertical gap between grid rows

    private static final int GRID_METRICS_DEBOUNCE_MS  = 150;
    private static final int THUMBNAIL_GEN_DEBOUNCE_MS = 150;

    private final FxmlLoader             fxmlLoader;
    private final UserPreferencesService userPreferencesService;
    private final PhotoRepository        photoRepository;
    private final ThumbnailRepository    thumbnailRepository;
    private final PhotoPreviewRepository photoPreviewRepository;
    private final AlbumPhotoRepository   albumPhotoRepository;
    private final SearchService          searchService;
    private final JobManager             jobManager;
    private final ModelPaths             modelPaths;
    private final TreeManager            treeManager;

    // ── FXML nodes ────────────────────────────────────────────────────────────

    @FXML
    public SplitPane                 librarySplitPane;
    @FXML
    public TreeView<LibraryTreeNode> libraryTreeView;
    @FXML
    public ListView<PhotoGridRow>    photoGridListView;
    @FXML
    public Slider                    thumbnailSizeSlider;
    @FXML
    public Label                     photoCountLabel;
    @FXML
    public TextField                 searchField;
    @FXML
    public Button                    searchButton;
    @FXML
    public Button                    clearSearchButton;
    @FXML
    public StackPane                 contentStack;
    @FXML
    public BorderPane                photoGridView;
    @FXML
    public AnchorPane                personDetailContainer;
    @FXML
    public AnchorPane                duplicatesContainer;
    @FXML
    public AnchorPane                folderDuplicatesContainer;

    @FXML
    public HBox        backgroundProgressContainer;
    @FXML
    public Label       backgroundProcessTitleLabel;
    @FXML
    public Button      backgroundProcessCancelButton;
    @FXML
    public StackPane   backgroundProgressBarContainer;
    @FXML
    public ProgressBar backgroundProgressBar;
    @FXML
    public Label       backgroundProgressLabel;
    @FXML
    public Label       backgroundProgressDescription;

    // ── Runtime state ─────────────────────────────────────────────────────────

    private DuplicatesController       duplicatesController;
    private FolderDuplicatesController folderDuplicatesController;

    /**
     * Guards against a stale background page-load or full-selection-load callback overwriting a
     * newer selection: every time the user switches selection (folder/timeline/album/search/
     * undated) this is incremented, and any in-flight callback whose captured value no longer
     * matches is discarded. Coarser than per-cell tracking — see implementation plan §4 ("only
     * folder/selection-switch staleness matters, not per-cell reassignment").
     */
    private final AtomicLong selectionGeneration = new AtomicLong();

    /**
     * The full, ordered photo set for the current selection — fetched once per selection change.
     * The grid is now fully virtualized (see {@link PhotoRowCell}), so unlike the old paged
     * {@code FlowPane} approach, all of it is "in" the grid immediately; only the rows actually
     * scrolled into view ever get a live cell or a thumbnail/preview lookup.
     */
    private List<PhotoRecord>  currentPhotos  = List.of();
    private Map<Long, Integer> photoIndexById = Map.of();

    /**
     * Current column count for the grid, so {@link #recomputeGridMetrics} can tell whether a
     * resize/slider change actually needs the rows regrouping, or whether only the (continuously
     * slider-bound) cell sizes changed.
     */
    private int lastColumns = -1;

    /**
     * Photo-id → cell controller for every currently *visible* cell — populated/cleared by
     * {@link #onRowShown}/{@link #onRowHidden} as rows scroll in and out of view. Used by
     * {@link #onThumbnailsReady} to swap a placeholder for the real thumbnail on any cell that's
     * still on-screen. A miss just means the photo has scrolled away since the request was made —
     * not an error — it'll get looked up fresh from the DB the next time its row is shown again.
     */
    private final Map<Long, PhotoCellController> visiblePhotoCells = new HashMap<>();

    /**
     * Photo IDs collected from visible rows that turned out to have no cached thumbnail yet,
     * batched up and flushed as a single {@code submitThumbnailGenerationJob} call after a short
     * debounce — see {@link #queueThumbnailGeneration}. Avoids firing one (mutually-superseding)
     * job per row during a fast scroll.
     */
    private final Set<Long>     pendingThumbnailGenIds = new HashSet<>();
    private final DelayedAction thumbnailGenDebounce   = new DelayedAction(THUMBNAIL_GEN_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    private final DelayedAction gridMetricsDebounce    = new DelayedAction(GRID_METRICS_DEBOUNCE_MS, TimeUnit.MILLISECONDS);

    private volatile boolean autoStartAiPipelineAfterModelDownload = false;

    /**
     * Lazily loaded on first PERSON selection.
     * The FXML + controller are created once and reused for every subsequent person.
     */
    private PersonDetailController personDetailController;

    // ── Initialisation ────────────────────────────────────────────────────────

    @Override
    @SneakyThrows
    public void initialize(URL location, ResourceBundle resources) {
        libraryTreeView.setCellFactory(tv -> new LibraryTreeCell());
        libraryTreeView.getSelectionModel()
                       .selectedItemProperty()
                       .addListener((obs, oldItem, newItem) -> onTreeSelectionChanged(newItem));

        treeManager.initialize(libraryTreeView);

        // Allow pressing Enter in the search field to trigger search
        searchField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                onSearchClicked(null);
            }
        });

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

        treeManager.onLibraryDataUpdated(null);

        loadFxmlAndAttachToParent(duplicatesContainer, FxmlView.DUPLICATES, v -> duplicatesController = v);
        loadFxmlAndAttachToParent(folderDuplicatesContainer, FxmlView.FOLDER_DUPLICATES, v -> folderDuplicatesController = v);
        checkModelsAndPromptDownload();
    }

    // ── Window / split-pane preferences ──────────────────────────────────────

    public void setUserPrefs(Stage primaryStage) {
        userPreferencesService.restoreWindowState(primaryStage);
        DelayedAction delayedSaveWindowSize = new DelayedAction(500, TimeUnit.MILLISECONDS);
        InvalidationListener invalidationListener = o ->
                delayedSaveWindowSize.reSchedule(() ->
                        userPreferencesService.saveWindowState(primaryStage));

        primaryStage.widthProperty()
                    .addListener(invalidationListener);
        primaryStage.heightProperty()
                    .addListener(invalidationListener);
        primaryStage.xProperty()
                    .addListener(invalidationListener);
        primaryStage.yProperty()
                    .addListener(invalidationListener);
        primaryStage.maximizedProperty()
                    .addListener(invalidationListener);

        librarySplitPane.setDividerPositions(userPreferencesService.getDividerPositions());
        DelayedAction delayedSaveDividerPosition = new DelayedAction(500, TimeUnit.MILLISECONDS);
        InvalidationListener splitPanePositionListener = o ->
                delayedSaveDividerPosition.reSchedule(() ->
                        userPreferencesService.saveSplitPositions(librarySplitPane.getDividerPositions()));
        librarySplitPane.getDividers()
                        .getFirst()
                        .positionProperty()
                        .addListener(splitPanePositionListener);
    }

    // ── Background process events ─────────────────────────────────────────────

    @EventListener
    public void onBackgroundProcessEvent(BackgroundProcessEvent event) {
        boolean                  terminal = event.isTerminal();
        BackgroundProcessPayload payload  = event.getPayload();

        runOnFxThread(() -> {
            backgroundProcessTitleLabel.setText(payload.getProcessName());
            backgroundProgressContainer.setVisible(!terminal);
            backgroundProgressBar.setProgress(payload.hasProgress()
                    ? payload.getProgressNormalized()
                    : INDETERMINATE_PROGRESS);
            backgroundProgressLabel.setText(payload.hasProgress() ? payload.getProgressText() : "");
            backgroundProgressDescription.setText(payload.getProgressDetails());
        });

        if (event.getEventType() != BackgroundProcessEventType.ENDED) {
            return;
        }
        if (!ModelDownloadJob.PROCESS_NAME.equals(event.getPayload()
                                                       .getProcessName())) {
            return;
        }
        if (autoStartAiPipelineAfterModelDownload) {
            autoStartAiPipelineAfterModelDownload = false;
            jobManager.submitAiPipelineJob();
        }
    }

    @FXML
    public void onCancelBackgroundJob(ActionEvent actionEvent) {
        jobManager.interruptCurrentJob();
    }

    // ── Tree selection ────────────────────────────────────────────────────────
    private void onTreeSelectionChanged(TreeItem<LibraryTreeNode> selectedItem) {
        if (selectedItem == null || selectedItem.getValue() == null) {
            showPhotoGrid();
            clearPhotoGrid();
            return;
        }
        if (selectedItem.getValue()
                        .type() == NodeType.DUPLICATES_FILE_ROOT) {
            clearSearchState();
            showDuplicatesView();
            return;
        }
        if (selectedItem.getValue()
                        .type() == NodeType.DUPLICATES_FOLDER_ROOT) {
            clearSearchState();
            showFolderDuplicatesView();
            return;
        }
        NodePayload payload = selectedItem.getValue()
                                          .payload();
        if (payload == null) {
            showPhotoGrid();
            clearPhotoGrid();
            return;
        }
        clearSearchState();
        switch (payload) {
            case FolderPayload fp -> {
                showPhotoGrid();
                loadPhotosForFolder(fp.folderId());
            }
            case TimelinePayload tp when tp.month() != null -> {
                showPhotoGrid();
                loadPhotosForTimeline(tp.year(), tp.month(), tp.day());
            }
            case TimelinePayload ignored -> {
                showPhotoGrid();
                clearPhotoGrid();
            }
            case UndatedPayload ignored -> {
                showPhotoGrid();
                loadPhotosUndated();
            }
            case AlbumPayload ap -> {
                showPhotoGrid();
                loadPhotosForAlbum(ap.albumId());
            }
            case PersonPayload pp -> showPersonDetail(pp.personId());
        }
    }

    // ── Person detail panel ───────────────────────────────────────────────────

    private void showPersonDetail(long personId) {
        if (personDetailController == null) {
            LoadedFxml<PersonDetailController> loaded = fxmlLoader.load(FxmlView.PERSON_DETAIL, null);
            personDetailController = loaded.controller();
            Parent view = loaded.parent();
            AnchorPane.setTopAnchor(view, 0.0);
            AnchorPane.setBottomAnchor(view, 0.0);
            AnchorPane.setLeftAnchor(view, 0.0);
            AnchorPane.setRightAnchor(view, 0.0);
            personDetailContainer.getChildren()
                                 .setAll(view);
        }
        photoGridView.setVisible(false);
        photoGridView.setManaged(false);
        duplicatesContainer.setVisible(false);
        duplicatesContainer.setManaged(false);
        folderDuplicatesContainer.setVisible(false);
        folderDuplicatesContainer.setManaged(false);
        personDetailContainer.setVisible(true);
        personDetailContainer.setManaged(true);
        personDetailController.loadPerson(personId);
    }

    private void showPhotoGrid() {
        personDetailContainer.setVisible(false);
        personDetailContainer.setManaged(false);
        duplicatesContainer.setVisible(false);
        duplicatesContainer.setManaged(false);
        folderDuplicatesContainer.setVisible(false);
        folderDuplicatesContainer.setManaged(false);
        photoGridView.setVisible(true);
        photoGridView.setManaged(true);
    }

    /**
     * Shows the file-level duplicates-review panel (content loaded from duplicates.fxml into
     * {@link #duplicatesContainer} in {@link #initialize}) and re-activates it so it picks up
     * the current duplicate set, mirroring the old tab-selection listener's behaviour.
     */
    private void showDuplicatesView() {
        personDetailContainer.setVisible(false);
        personDetailContainer.setManaged(false);
        photoGridView.setVisible(false);
        photoGridView.setManaged(false);
        folderDuplicatesContainer.setVisible(false);
        folderDuplicatesContainer.setManaged(false);
        duplicatesContainer.setVisible(true);
        duplicatesContainer.setManaged(true);
        duplicatesController.activateDuplicatesView();
    }

    /**
     * Shows the folder-level duplicates-review panel (content loaded from folder_duplicates.fxml
     * into {@link #folderDuplicatesContainer} in {@link #initialize}) and re-activates it so it
     * picks up the current set of folder pairs.
     */
    private void showFolderDuplicatesView() {
        personDetailContainer.setVisible(false);
        personDetailContainer.setManaged(false);
        photoGridView.setVisible(false);
        photoGridView.setManaged(false);
        duplicatesContainer.setVisible(false);
        duplicatesContainer.setManaged(false);
        folderDuplicatesContainer.setVisible(true);
        folderDuplicatesContainer.setManaged(true);
        folderDuplicatesController.activateFolderDuplicatesView();
    }

    // ── Photo loading ─────────────────────────────────────────────────────────

    private void loadPhotosForFolder(long folderId) {
        long myGeneration = selectionGeneration.incrementAndGet();
        runOnDaemonThread("LoadFolder", () -> {
            List<PhotoRecord> photos = photoRepository.findByFolderId(folderId);
            loadSelectionResult(myGeneration, photos);
        });
    }

    private void loadPhotosForTimeline(int year, int month, Integer day) {
        long myGeneration = selectionGeneration.incrementAndGet();
        runOnDaemonThread("LoadTimeline", () -> {
            List<PhotoRecord> photos = photoRepository.findByCaptureDate(year, month, day);
            loadSelectionResult(myGeneration, photos);
        });
    }

    private void loadPhotosUndated() {
        long myGeneration = selectionGeneration.incrementAndGet();
        runOnDaemonThread("LoadUndated", () -> {
            List<PhotoRecord> photos = photoRepository.findByNullCaptureDate();
            loadSelectionResult(myGeneration, photos);
        });
    }

    private void loadPhotosForAlbum(long albumId) {
        long myGeneration = selectionGeneration.incrementAndGet();
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

    /**
     * Applies a freshly-loaded photo set to the grid, unless the user has since switched to a
     * different selection (see {@link #selectionGeneration}), in which case it's silently dropped.
     */
    private void loadSelectionResult(long myGeneration, List<PhotoRecord> photos) {
        runOnFxThread(() -> {
            if (myGeneration == selectionGeneration.get()) {
                populatePhotoGrid(photos);
            }
        });
    }

    // ── Search ────────────────────────────────────────────────────────────────

    @FXML
    public void onSearchClicked(ActionEvent event) {
        String query = searchField.getText();
        if (query == null || query.isBlank()) {
            return;
        }
        clearSearchButton.setVisible(true);
        showPhotoGrid();
        libraryTreeView.getSelectionModel()
                       .clearSelection();
        long myGeneration = selectionGeneration.incrementAndGet();
        runOnDaemonThread("Search", () -> {
            try {
                List<Long> photoIds = searchService.semanticSearch(query, SEARCH_TOP_K);
                List<PhotoRecord> photos = photoIds.stream()
                                                   .map(id -> photoRepository.findById(id)
                                                                             .orElse(null))
                                                   .filter(Objects::nonNull)
                                                   .toList();
                runOnFxThread(() -> {
                    if (myGeneration != selectionGeneration.get()) {
                        return;
                    }
                    populatePhotoGrid(photos);
                    photoCountLabel.setText("Search: " + photos.size() + " results");
                });
            } catch (Exception e) {
                log.error("Semantic search failed for query '{}'", query, e);
                runOnFxThread(() -> {
                    if (myGeneration == selectionGeneration.get()) {
                        photoCountLabel.setText("Search error: " + e.getMessage());
                    }
                });
            }
        });
    }

    @FXML
    public void onClearSearchClicked(ActionEvent event) {
        clearSearchState();
        clearPhotoGrid();
    }

    private void clearSearchState() {
        searchField.clear();
        clearSearchButton.setVisible(false);
        photoCountLabel.setText("");
    }

    // ── Photo grid (virtualized) ────────────────────────────────────────────────

    /**
     * Applies a freshly-loaded, fully-ordered photo set to the grid. Unlike the old paged
     * {@code FlowPane} approach, the entire set is handed to the (virtualized) {@code ListView}
     * right away — only {@link #recomputeGridMetrics} decides how it's chunked into rows, and only
     * rows that actually scroll into view ever get a live cell or a thumbnail/preview lookup.
     */
    private void populatePhotoGrid(List<PhotoRecord> photos) {
        visiblePhotoCells.clear();
        pendingThumbnailGenIds.clear();
        currentPhotos = photos;
        photoIndexById = CollectionUtils.getIdToIndexMap(photos);
        photoCountLabel.setText(photos.size() + " photo" + (photos.size() == 1 ? "" : "s"));
        recomputeGridMetrics(true); // force a regroup even if the column count is unchanged
    }

    private void clearPhotoGrid() {
        selectionGeneration.incrementAndGet();
        visiblePhotoCells.clear();
        pendingThumbnailGenIds.clear();
        currentPhotos = List.of();
        photoIndexById = Map.of();
        photoGridListView.getItems()
                         .clear();
        photoCountLabel.setText("");
    }

    /**
     * Recomputes columns-per-row (from viewport width) and row height (from thumbnail size), and
     * updates {@link ListView#setFixedCellSize} — telling the ListView every row is exactly that
     * tall lets its virtual flow skip per-cell measurement, which matters a lot once there are
     * thousands of rows. Only actually regroups {@link #currentPhotos} into {@link PhotoGridRow}s
     * (an O(n) rebuild of the item list) if the column count changed or {@code force} is set —
     * a resize that doesn't change the column count, or a slider drag, shouldn't re-trigger it.
     */
    // FIXME: duplicate
    private void recomputeGridMetrics(boolean force) {
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
    private void regroupIntoRows(int columns) {
        List<PhotoGridRow> rows = new ArrayList<>();
        for (int i = 0; i < currentPhotos.size(); i += columns) {
            rows.add(new PhotoGridRow(currentPhotos.subList(i, Math.min(i + columns, currentPhotos.size()))));
        }
        photoGridListView.getItems()
                         .setAll(rows);
    }

    // ── PhotoGridCallbacks — the narrow surface PhotoRowCell/PhotoGridRowController use ────────

    @Override
    public ObservableValue<Number> thumbnailSizeProperty() {
        return thumbnailSizeSlider.valueProperty();
    }

    @Override
    public void onPhotoClicked(PhotoRecord photo) {
        Integer idx = photoIndexById.get(photo.getId());
        if (idx != null) {
            openSlideshow(currentPhotos, idx);
        }
    }

    @Override
    public String tooltipTextFor(PhotoRecord photo) {
        return getPhotoDetailsText(photo);
    }

    /**
     * A row just became visible (or was re-flowed while still visible). Registers its photos in
     * {@link #visiblePhotoCells} so {@link #onThumbnailsReady} can reach them directly, then looks
     * up thumbnails/previews for exactly these photo IDs on a background thread and applies them.
     * <p>
     * Both {@link #selectionGeneration} and the row's own {@code showToken} are re-checked before
     * touching any cell: the former guards against a selection switch, the latter against this
     * same row controller having been recycled to a *different* set of photos (e.g. a fast scroll)
     * while the lookup was in flight.
     */
    @Override
    public void onRowShown(PhotoGridRowController row, List<PhotoRecord> photos) {
        PersonDetailController.RowInfo rowInfo = getRowInfo(row, photos, visiblePhotoCells, selectionGeneration);


        runOnDaemonThread("Thumbnails", () -> {
            Map<Long, ThumbnailRecord>    thumbs   = thumbnailRepository.findByPhotoIds(rowInfo.ids());
            Map<Long, PhotoPreviewRecord> previews = photoPreviewRepository.findByPhotoIds(rowInfo.ids());
            runOnFxThread(() -> {
                if (rowInfo.myGeneration() != selectionGeneration.get() || rowInfo.myShowToken() != row.getShowToken()) {
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

    /**
     * Batches up photo IDs missing a real thumbnail and flushes them as a single
     * {@code submitThumbnailGenerationJob} call after a short debounce, instead of firing one call
     * per row shown — {@code ThumbnailGenerationJob} is supersedable, so one call per row during a
     * fast scroll would otherwise mean each newly-visible row cancels the previous row's
     * still-useful (still on-screen) request.
     */
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

    /**
     * Swaps the placeholder/quick-preview image of any still-*visible* cell for the real
     * thumbnail, once {@code ThumbnailGenerationJob} has generated it. Photo IDs not currently in
     * {@link #visiblePhotoCells} (scrolled out of view since the request was made) are simply
     * skipped — not an error under virtualization, since that row will do a fresh, correct lookup
     * the next time it's shown (see {@link #onRowShown}).
     */
    @EventListener
    public void onThumbnailsReady(ThumbnailsReadyEvent event) {
        ThumbnailUtils.updateThumbnailImage(thumbnailRepository, visiblePhotoCells, event);
    }

    private Image loadPreviewImage(byte[] previewBytes) {
        return new Image(new ByteArrayInputStream(previewBytes), MAX_THUMBNAIL_DECODE_SIZE, MAX_THUMBNAIL_DECODE_SIZE, true, true);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    // ── Menu actions ──────────────────────────────────────────────────────────

    /**
     * Existing: single-root rescan via the path-entry modal.
     */
    @FXML
    @SneakyThrows
    public void onRescanMenuClicked(ActionEvent actionEvent) {
        Stage stage = new Stage();
        Parent root = fxmlLoader.load(FxmlView.RE_SCAN_MODAL, new RescanBundle("D:\\Programming\\sample-data"))
                                .parent();
        stage.setScene(new Scene(root));
        stage.setTitle("Rescan library");
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initOwner(backgroundProcessCancelButton.getScene()
                                                     .getWindow());
        stage.showAndWait();
    }

    @FXML
    @SneakyThrows
    public void onRescanRootsMenuClicked(ActionEvent actionEvent) {
        Stage stage = new Stage();
        Parent root = fxmlLoader.load(FxmlView.RESCAN_ROOTS, null)
                                .parent();
        stage.setScene(new Scene(root));
        stage.setTitle("Rescan existing roots");
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initOwner(backgroundProcessCancelButton.getScene()
                                                     .getWindow());
        stage.showAndWait();
    }

    /**
     * NEW: opens the Add Files dialog with no pre-fill (menu-triggered path).
     * DnD-triggered opens go through {@link #onTreeDragDropped}.
     */
    @FXML
    @SneakyThrows
    public void onAddFilesMenuClicked(ActionEvent actionEvent) {
        openAddFilesDialog(new AddFilesBundle(List.of(), null));
    }

    @FXML
    public void onFindDuplicates(ActionEvent event) {
        jobManager.submitDuplicatesJob();
    }

    @FXML
    public void onTriggerAiPipeline(ActionEvent event) {
        if (modelPaths.allModelsPresent()) {
            jobManager.submitAiPipelineJob();
            return;
        }

        boolean confirmation = AlertHelper.confirm(
                null,
                "Download AI models",
                "AI features need model files (~1 GB)",
                "Face recognition and semantic search require AI model files that "
                        + "haven't been downloaded yet. Download them now in the background?"
        );

        if (confirmation) {
            autoStartAiPipelineAfterModelDownload = true;
            jobManager.submitModelDownloadJob(() -> {});
        }
    }

    // ── Drag-and-drop onto the library TreeView ───────────────────────────────

    /**
     * Accepts a drag if the dragboard contains at least one file.
     * Highlights the tree view with a COPY cursor to give clear visual feedback.
     */
    @FXML
    public void onTreeDragOver(DragEvent event) {
        Dragboard db = event.getDragboard();
        if (db.hasFiles()) {
            event.acceptTransferModes(TransferMode.COPY);
        }
        event.consume();
    }

    /**
     * Handles a file/folder drop onto the library tree.
     * <p>
     * Resolves the drop target node to determine whether the user dropped onto
     * an {@code IMPORT_ROOT} or {@code FOLDER} node — if so, that node's root
     * path is pre-filled as the destination in the Add Files dialog. Any other
     * drop target (header nodes, timeline, etc.) opens the dialog with no
     * pre-fill, letting the user choose the destination manually.
     */
    @FXML
    public void onTreeDragDropped(DragEvent event) {
        Dragboard db = event.getDragboard();
        if (!db.hasFiles()) {
            event.setDropCompleted(false);
            event.consume();
            return;
        }

        List<String> droppedPaths = db.getFiles()
                                      .stream()
                                      .map(File::getAbsolutePath)
                                      .toList();

        // ── Resolve the drop-target node ──────────────────────────────────────
        String prefilledRoot = resolveDropTargetRootPath(event);

        AddFilesBundle bundle = new AddFilesBundle(droppedPaths, prefilledRoot);
        openAddFilesDialog(bundle);

        event.setDropCompleted(true);
        event.consume();
    }

    /**
     * Inspects the node under the mouse at the time of the drop. Returns the
     * import-root path if the node is an {@code IMPORT_ROOT} or {@code FOLDER},
     * {@code null} otherwise.
     */
    private String resolveDropTargetRootPath(DragEvent event) {
        // Pick the cell directly under the cursor
        Node picked = event.getPickResult()
                           .getIntersectedNode();
        while (picked != null && !(picked instanceof TreeCell)) {
            picked = picked.getParent();
        }
        if (picked instanceof TreeCell<?> cell) {
            Object value = cell.getItem();
            if (value instanceof LibraryTreeNode node) {
                NodeType type = node.type();
                if (type == NodeType.IMPORT_ROOT) {
                    // The node label IS the root path
                    return node.displayName();
                }
                if (type == NodeType.FOLDER) {
                    // Walk up the tree to find the enclosing IMPORT_ROOT
                    TreeItem<LibraryTreeNode> item = libraryTreeView.getRoot();
                    return findImportRootPath(item, node.displayName());
                }
            }
        }
        return null; // user dropped on a non-folder node; dialog will ask
    }

    /**
     * Walks the tree depth-first to find the {@code IMPORT_ROOT} ancestor of a
     * node whose label matches {@code targetLabel}. Returns the root's label
     * (which equals its path) or {@code null} if not found.
     */
    private String findImportRootPath(TreeItem<LibraryTreeNode> subtree, String targetLabel) {
        if (subtree == null) {
            return null;
        }
        for (TreeItem<LibraryTreeNode> child : subtree.getChildren()) {
            LibraryTreeNode node = child.getValue();
            if (node == null) {
                continue;
            }
            if (node.type() == NodeType.IMPORT_ROOT) {
                // Search this root's subtree
                if (containsLabel(child, targetLabel)) {
                    return node.displayName();
                }
            } else {
                String found = findImportRootPath(child, targetLabel);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private boolean containsLabel(TreeItem<LibraryTreeNode> subtree, String label) {
        if (subtree == null) {
            return false;
        }
        LibraryTreeNode val = subtree.getValue();
        if (val != null && label.equals(val.displayName())) {
            return true;
        }
        for (TreeItem<LibraryTreeNode> child : subtree.getChildren()) {
            if (containsLabel(child, label)) {
                return true;
            }
        }
        return false;
    }

    // ── Add Files dialog helper ───────────────────────────────────────────────

    @SneakyThrows
    private void openAddFilesDialog(AddFilesBundle bundle) {
        Stage stage = new Stage();
        Parent root = fxmlLoader.load(FxmlView.ADD_FILES, bundle)
                                .parent();
        stage.setScene(new Scene(root));
        stage.setTitle("Add files / folders");
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initOwner(backgroundProcessCancelButton.getScene()
                                                     .getWindow());
        stage.showAndWait();
    }

    private void openSlideshow(List<PhotoRecord> photos, int startIndex) {
        StageUtils.openSlideshow(photos, startIndex, photoGridListView.getScene(), fxmlLoader);
    }

    private void checkModelsAndPromptDownload() {
        if (modelPaths.allModelsPresent()) {
            return;
        }

        boolean confirmation = AlertHelper.confirm(
                null,
                "Download AI models",
                "AI features need model files (~1 GB)",
                "Face recognition and semantic search require AI model files that "
                        + "haven't been downloaded yet. Download them now in the background?"
        );

        if (confirmation) {
            jobManager.submitModelDownloadJob(() -> {});
        }
    }


    private <T> void loadFxmlAndAttachToParent(Pane parent, FxmlView<T> view, Consumer<T> controllerConsumer) {
        LoadedFxml<T> loadedFolderDuplicates = fxmlLoader.load(view, null);
        controllerConsumer.accept(loadedFolderDuplicates.controller());
        Parent viewPane = loadedFolderDuplicates.parent();
        AnchorPane.setTopAnchor(viewPane, 0.0);
        AnchorPane.setBottomAnchor(viewPane, 0.0);
        AnchorPane.setLeftAnchor(viewPane, 0.0);
        AnchorPane.setRightAnchor(viewPane, 0.0);
        parent.getChildren()
              .setAll(viewPane);
    }
}
