package com.github.curiousoddman.curious_images.ui.controller.screen;

// FIXME: Not this file, but somewhere is thread running that does not allow JVM to stop. Need to find and fix that.
// FIXME: After AI pipeline finishes face detection - albums of faces are not immediately shown - require app restart.
// FIXME: Person faces are strange - looks like those are not always correct.
// FIXME: Person albums do not trigger thumbnails generation and, besides, show NO IMAGE, rather than Loading thumbnail...

import com.github.curiousoddman.curious_images.dbobj.tables.records.AlbumRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.FolderRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ImportRootRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PersonRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoPreviewRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ThumbnailRecord;
import com.github.curiousoddman.curious_images.domain.search.SearchService;
import com.github.curiousoddman.curious_images.domain.user.prefs.UserPreferencesService;
import com.github.curiousoddman.curious_images.event.model.AiPipelineCompleteEvent;
import com.github.curiousoddman.curious_images.event.model.BackgroundProcessEvent;
import com.github.curiousoddman.curious_images.event.model.LibraryUpdatedEvent;
import com.github.curiousoddman.curious_images.event.model.ThumbnailsReadyEvent;
import com.github.curiousoddman.curious_images.event.payload.BackgroundProcessPayload;
import com.github.curiousoddman.curious_images.model.LoadedFxml;
import com.github.curiousoddman.curious_images.model.TimelineData;
import com.github.curiousoddman.curious_images.model.bundle.AddFilesBundle;
import com.github.curiousoddman.curious_images.model.bundle.RescanBundle;
import com.github.curiousoddman.curious_images.persistence.AlbumPhotoRepository;
import com.github.curiousoddman.curious_images.persistence.AlbumRepository;
import com.github.curiousoddman.curious_images.persistence.FolderRepository;
import com.github.curiousoddman.curious_images.persistence.ImportRootRepository;
import com.github.curiousoddman.curious_images.persistence.PersonRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoPreviewRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoRepository;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.FxmlView;
import com.github.curiousoddman.curious_images.ui.controller.custom.PhotoCellController;
import com.github.curiousoddman.curious_images.ui.controller.custom.PhotoGridRowController;
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
import com.github.curiousoddman.curious_images.util.HumanReadableUtils;
import com.github.curiousoddman.curious_images.util.async.DelayedAction;
import com.github.curiousoddman.curious_images.util.async.jobs.JobManager;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ObservableList;
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
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
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
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
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
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.curiousoddman.curious_images.ui.controller.screen.DuplicatesController.getPhotoDetailsText;
import static com.sun.javafx.util.Utils.runOnFxThread;
import static javafx.scene.control.ProgressIndicator.INDETERMINATE_PROGRESS;

@Lazy
@Slf4j
@Component
@RequiredArgsConstructor
public class LibraryController implements Initializable, PhotoGridCallbacks {
    public static final Font CONSOLAS = new Font("Consolas", 15);

    private static final int SEARCH_TOP_K = 50;

    /**
     * Thumbnail files are decoded at most at this size regardless of the slider's actual value —
     * matches {@code thumbnailSizeSlider}'s max (see library.fxml) so JavaFX never decodes more
     * pixels than the grid could ever display, however large the on-disk cached thumbnail is.
     */
    private static final int MAX_THUMBNAIL_DECODE_SIZE = 320;

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
    private final ImportRootRepository   importRootRepository;
    private final FolderRepository       folderRepository;
    private final PhotoRepository        photoRepository;
    private final ThumbnailRepository    thumbnailRepository;
    private final PhotoPreviewRepository photoPreviewRepository;
    private final AlbumRepository        albumRepository;
    private final AlbumPhotoRepository   albumPhotoRepository;
    private final PersonRepository       personRepository;
    private final SearchService          searchService;
    private final JobManager             jobManager;

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
    public TabPane                   mainTabPane;
    @FXML
    public Tab                       duplicatesTab;
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

    private Image                noImageAvailable;
    private DuplicatesController duplicatesController;

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

    /**
     * Lazily loaded on first PERSON selection.
     * The FXML + controller are created once and reused for every subsequent person.
     */
    private PersonDetailController personDetailController;

    // ── Initialisation ────────────────────────────────────────────────────────

    @Override
    @SneakyThrows
    public void initialize(URL location, ResourceBundle resources) {
        noImageAvailable = new Image(getClass().getResourceAsStream("/img/noimage.png"));

        libraryTreeView.setCellFactory(tv -> new LibraryTreeCell());
        libraryTreeView.getSelectionModel()
                       .selectedItemProperty()
                       .addListener((obs, oldItem, newItem) -> onTreeSelectionChanged(newItem));

        // Allow pressing Enter in the search field to trigger search
        searchField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                onSearchClicked(null);
            }
        });

        // Virtualized photo grid: ListView only ever instantiates enough PhotoRowCells to cover
        // the viewport plus a small buffer, recycling them on scroll — this replaces the old
        // ever-growing FlowPane (see the FIXME this used to carry) with a node count bounded by
        // viewport size, not selection size.
        photoGridListView.setCellFactory(lv -> new PhotoRowCell(this));
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

        onLibraryDataUpdated(null);

        LoadedFxml<DuplicatesController> loaded = fxmlLoader.load(FxmlView.DUPLICATES, null);
        duplicatesTab.setContent(loaded.parent());
        duplicatesController = loaded.controller();

        mainTabPane.getSelectionModel()
                   .selectedItemProperty()
                   .addListener((obs, oldTab, newTab) -> {
                       if (newTab == duplicatesTab) {
                           duplicatesController.activate(noImageAvailable);
                       }
                   });
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
    }

    @FXML
    public void onCancelBackgroundJob(ActionEvent actionEvent) {
        jobManager.interruptCurrentJob();
    }

    // ── Tree building ─────────────────────────────────────────────────────────

    /**
     * Full tree rebuild. Called on startup and after each {@link LibraryUpdatedEvent}.
     * Albums / persons sections are included if data exists (gracefully empty otherwise).
     */
    @SneakyThrows
    @EventListener
    public void onLibraryDataUpdated(LibraryUpdatedEvent event) {
        log.info("Rebuilding library tree");
        Thread t = new Thread(() -> {
            List<TreeItem<LibraryTreeNode>> folderItems   = buildImportRootItems();
            List<TreeItem<LibraryTreeNode>> timelineItems = buildTimelineItems();
            List<TreeItem<LibraryTreeNode>> albumItems    = buildAlbumItems();
            List<TreeItem<LibraryTreeNode>> personItems   = buildPersonItems();

            runOnFxThread(() -> {
                TreeItem<LibraryTreeNode> foldersRoot = treeItem(
                        new LibraryTreeNode("Folders", null, NodeType.FOLDERS_ROOT));
                foldersRoot.getChildren()
                           .setAll(folderItems);
                foldersRoot.setExpanded(true);

                TreeItem<LibraryTreeNode> timelineRoot = treeItem(
                        new LibraryTreeNode("Timeline", null, NodeType.TIMELINE_ROOT));
                timelineRoot.getChildren()
                            .setAll(timelineItems);
                timelineRoot.setExpanded(true);

                TreeItem<LibraryTreeNode> albumsRoot = treeItem(
                        new LibraryTreeNode("Albums", null, NodeType.ALBUMS_ROOT));
                albumsRoot.getChildren()
                          .setAll(albumItems);
                albumsRoot.setExpanded(!albumItems.isEmpty());

                TreeItem<LibraryTreeNode> personsRoot = treeItem(
                        new LibraryTreeNode("People", null, NodeType.PERSONS_ROOT));
                personsRoot.getChildren()
                           .setAll(personItems);
                personsRoot.setExpanded(!personItems.isEmpty());

                TreeItem<LibraryTreeNode> invisibleRoot = new TreeItem<>();
                invisibleRoot.getChildren()
                             .setAll(foldersRoot, timelineRoot, albumsRoot, personsRoot);
                libraryTreeView.setRoot(invisibleRoot);
            });
        });
        t.setDaemon(true);
        t.start();
    }

    /**
     * Refreshes the Albums and People sections of the tree when the AI pipeline completes.
     * Cheaper than a full rebuild: leaves the Folders and Timeline sections untouched.
     */
    @EventListener
    public void onAiPipelineComplete(AiPipelineCompleteEvent event) {
        // FIXME: Albums - are those needed = person albums - doubt so
        log.info("AI pipeline complete — refreshing Albums and People tree sections");
        Thread t = new Thread(() -> {
            List<TreeItem<LibraryTreeNode>> albumItems  = buildAlbumItems();
            List<TreeItem<LibraryTreeNode>> personItems = buildPersonItems();
            runOnFxThread(() -> {
                TreeItem<LibraryTreeNode> root = libraryTreeView.getRoot();
                if (root == null) {
                    return;
                }
                ObservableList<TreeItem<LibraryTreeNode>> children = root.getChildren();
                if (children.size() < 4) {
                    return;
                }
                children.get(2)
                        .getChildren()
                        .setAll(albumItems);
                children.get(3)
                        .getChildren()
                        .setAll(personItems);
            });
        });
        t.setDaemon(true);
        t.start();
    }

    // ── Tree builders ─────────────────────────────────────────────────────────

    private List<TreeItem<LibraryTreeNode>> buildImportRootItems() {
        List<TreeItem<LibraryTreeNode>> rootItems = new ArrayList<>();
        for (ImportRootRecord importRoot : importRootRepository.findAll()) {
            FolderRecord rootFolder = folderRepository.findRootFolder(importRoot.getId())
                                                      .orElse(null);
            Long        rootFolderId = rootFolder == null ? null : rootFolder.getId();
            NodePayload payload      = rootFolderId == null ? null : new FolderPayload(rootFolderId);
            TreeItem<LibraryTreeNode> folderRootItem = treeItem(
                    new LibraryTreeNode(importRoot.getPath(), payload, NodeType.IMPORT_ROOT));
            if (rootFolderId != null) {
                folderRootItem.getChildren()
                              .addAll(buildFolderItems(rootFolderId));
            }
            rootItems.add(folderRootItem);
        }
        return rootItems;
    }

    private List<TreeItem<LibraryTreeNode>> buildFolderItems(long parentFolderId) {
        List<TreeItem<LibraryTreeNode>> items = new ArrayList<>();
        for (FolderRecord folder : folderRepository.findChildren(parentFolderId)) {
            TreeItem<LibraryTreeNode> item = treeItem(
                    new LibraryTreeNode(folder.getName(),
                            new FolderPayload(folder.getId()),
                            NodeType.FOLDER));
            item.getChildren()
                .addAll(buildFolderItems(folder.getId()));
            items.add(item);
        }
        return items;
    }

    private List<TreeItem<LibraryTreeNode>> buildTimelineItems() {
        TimelineData data = photoRepository.findTimelineData();

        Map<Integer, Map<Integer, List<TimelineData.TimelineDay>>> byYearMonth = new LinkedHashMap<>();
        for (TimelineData.TimelineDay day : data.days()) {
            byYearMonth.computeIfAbsent(day.year(), y -> new LinkedHashMap<>())
                       .computeIfAbsent(day.month(), m -> new ArrayList<>())
                       .add(day);
        }

        List<TreeItem<LibraryTreeNode>> items = new ArrayList<>();

        for (var yearEntry : byYearMonth.entrySet()) {
            int year = yearEntry.getKey();
            int yearCount = yearEntry.getValue()
                                     .values()
                                     .stream()
                                     .flatMap(List::stream)
                                     .mapToInt(TimelineData.TimelineDay::count)
                                     .sum();

            TreeItem<LibraryTreeNode> yearItem = treeItem(new LibraryTreeNode(
                    year + " (" + yearCount + ")",
                    new TimelinePayload(year, null, null),
                    NodeType.TIMELINE_YEAR));

            for (var monthEntry : yearEntry.getValue()
                                           .entrySet()) {
                int month = monthEntry.getKey();
                int monthCount = monthEntry.getValue()
                                           .stream()
                                           .mapToInt(TimelineData.TimelineDay::count)
                                           .sum();
                String monthName = Month.of(month)
                                        .getDisplayName(TextStyle.FULL, Locale.getDefault());

                TreeItem<LibraryTreeNode> monthItem = treeItem(new LibraryTreeNode(
                        monthName + " (" + monthCount + ")",
                        new TimelinePayload(year, month, null),
                        NodeType.TIMELINE_MONTH));

                for (TimelineData.TimelineDay day : monthEntry.getValue()) {
                    monthItem.getChildren()
                             .add(treeItem(new LibraryTreeNode(
                                     day.day() + " (" + day.count() + ")",
                                     new TimelinePayload(year, month, day.day()),
                                     NodeType.TIMELINE_DAY)));
                }
                yearItem.getChildren()
                        .add(monthItem);
            }
            items.add(yearItem);
        }

        if (data.undatedCount() > 0) {
            items.add(treeItem(new LibraryTreeNode(
                    "Undated (" + data.undatedCount() + ")",
                    new UndatedPayload(),
                    NodeType.TIMELINE_UNDATED)));
        }
        return items;
    }

    private List<TreeItem<LibraryTreeNode>> buildAlbumItems() {
        List<TreeItem<LibraryTreeNode>> items  = new ArrayList<>();
        Map<String, List<AlbumRecord>>  byType = new LinkedHashMap<>();
        for (AlbumRecord album : albumRepository.findAll()) {
            byType.computeIfAbsent(album.getType(), k -> new ArrayList<>())
                  .add(album);
        }
        for (var entry : byType.entrySet()) {
            NodeType nodeType = switch (entry.getKey()) {
                case "EVENT" -> NodeType.ALBUM_EVENT;
                case "LOCATION" -> NodeType.ALBUM_LOCATION;
                case "SIMILARITY" -> NodeType.ALBUM_SIMILARITY;
                default -> NodeType.ALBUM_EVENT;
            };
            for (AlbumRecord album : entry.getValue()) {
                items.add(treeItem(new LibraryTreeNode(
                        album.getName(), new AlbumPayload(album.getId()), nodeType)));
            }
        }
        return items;
    }

    private List<TreeItem<LibraryTreeNode>> buildPersonItems() {
        List<TreeItem<LibraryTreeNode>> items = new ArrayList<>();
        for (PersonRecord person : personRepository.findAll()) {
            String label = person.getName() != null ? person.getName() : "Person #" + person.getId();
            items.add(treeItem(new LibraryTreeNode(
                    label, new PersonPayload(person.getId()), NodeType.PERSON)));
        }
        return items;
    }

    // ── Tree selection ────────────────────────────────────────────────────────

    private void onTreeSelectionChanged(TreeItem<LibraryTreeNode> selectedItem) {
        if (selectedItem == null || selectedItem.getValue() == null) {
            showPhotoGrid();
            clearPhotoGrid();
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
        personDetailContainer.setVisible(true);
        personDetailContainer.setManaged(true);
        personDetailController.loadPerson(personId);
    }

    private void showPhotoGrid() {
        personDetailContainer.setVisible(false);
        personDetailContainer.setManaged(false);
        photoGridView.setVisible(true);
        photoGridView.setManaged(true);
    }

    // ── Photo loading ─────────────────────────────────────────────────────────

    private void loadPhotosForFolder(long folderId) {
        long myGeneration = selectionGeneration.incrementAndGet();
        Thread t = new Thread(() -> {
            List<PhotoRecord> photos = photoRepository.findByFolderId(folderId);
            runOnFxThread(() -> loadSelectionResult(myGeneration, photos));
        });
        t.setDaemon(true);
        t.start();
    }

    private void loadPhotosForTimeline(int year, int month, Integer day) {
        long myGeneration = selectionGeneration.incrementAndGet();
        Thread t = new Thread(() -> {
            List<PhotoRecord> photos = photoRepository.findByCaptureDate(year, month, day);
            runOnFxThread(() -> loadSelectionResult(myGeneration, photos));
        });
        t.setDaemon(true);
        t.start();
    }

    private void loadPhotosUndated() {
        long myGeneration = selectionGeneration.incrementAndGet();
        Thread t = new Thread(() -> {
            List<PhotoRecord> photos = photoRepository.findByNullCaptureDate();
            runOnFxThread(() -> loadSelectionResult(myGeneration, photos));
        });
        t.setDaemon(true);
        t.start();
    }

    private void loadPhotosForAlbum(long albumId) {
        long myGeneration = selectionGeneration.incrementAndGet();
        Thread t = new Thread(() -> {
            List<Long> photoIds = albumPhotoRepository.findPhotoIdsByAlbumId(albumId);
            List<PhotoRecord> photos = photoIds.stream()
                                               .map(id -> photoRepository.findById(id)
                                                                         .orElse(null))
                                               .filter(Objects::nonNull)
                                               .toList();
            runOnFxThread(() -> loadSelectionResult(myGeneration, photos));
        });
        t.setDaemon(true);
        t.start();
    }

    /**
     * Applies a freshly-loaded photo set to the grid, unless the user has since switched to a
     * different selection (see {@link #selectionGeneration}), in which case it's silently dropped.
     */
    private void loadSelectionResult(long myGeneration, List<PhotoRecord> photos) {
        if (myGeneration == selectionGeneration.get()) {
            populatePhotoGrid(photos);
        }
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
        Thread t = new Thread(() -> {
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
        t.setDaemon(true);
        t.start();
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
        photoIndexById = buildIndexMap(photos);
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

    private static Map<Long, Integer> buildIndexMap(List<PhotoRecord> photos) {
        Map<Long, Integer> map = new HashMap<>(photos.size() * 2);
        for (int i = 0; i < photos.size(); i++) {
            map.put(photos.get(i)
                          .getId(), i);
        }
        return map;
    }

    /**
     * Recomputes columns-per-row (from viewport width) and row height (from thumbnail size), and
     * updates {@link ListView#setFixedCellSize} — telling the ListView every row is exactly that
     * tall lets its virtual flow skip per-cell measurement, which matters a lot once there are
     * thousands of rows. Only actually regroups {@link #currentPhotos} into {@link PhotoGridRow}s
     * (an O(n) rebuild of the item list) if the column count changed or {@code force} is set —
     * a resize that doesn't change the column count, or a slider drag, shouldn't re-trigger it.
     */
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
        return buildPhotoDetailsText(photo);
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
        for (PhotoCellController cell : row.getCellControllers()) {
            PhotoRecord shown = cell.getCurrentPhoto();
            if (shown != null) {
                visiblePhotoCells.put(shown.getId(), cell);
            }
        }

        long myGeneration = selectionGeneration.get();
        long myShowToken  = row.getShowToken();
        List<Long> ids = photos.stream()
                               .map(PhotoRecord::getId)
                               .toList();

        Thread t = new Thread(() -> {
            Map<Long, ThumbnailRecord>    thumbs   = thumbnailRepository.findByPhotoIds(ids);
            Map<Long, PhotoPreviewRecord> previews = photoPreviewRepository.findByPhotoIds(ids);
            runOnFxThread(() -> {
                if (myGeneration != selectionGeneration.get() || myShowToken != row.getShowToken()) {
                    return; // selection changed, or this row now shows different photos — discard
                }
                List<Long> missing = new ArrayList<>();
                for (PhotoRecord photo : photos) {
                    ThumbnailRecord thumbnail = thumbs.get(photo.getId());
                    if (thumbnail != null && hasCachedFile(thumbnail)) {
                        row.applyImage(photo, loadThumbnailImage(thumbnail));
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
        t.setDaemon(true);
        t.start();
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
        List<Long> ids = List.copyOf(event.getPhotoIds());
        Thread t = new Thread(() -> {
            Map<Long, ThumbnailRecord> thumbs = thumbnailRepository.findByPhotoIds(ids);
            runOnFxThread(() -> {
                for (Map.Entry<Long, ThumbnailRecord> entry : thumbs.entrySet()) {
                    PhotoCellController cell = visiblePhotoCells.get(entry.getKey());
                    if (cell != null && hasCachedFile(entry.getValue())) {
                        cell.showImage(cell.getCurrentPhoto(), loadThumbnailImage(entry.getValue()));
                    }
                }
            });
        });
        t.setDaemon(true);
        t.start();
    }

    private static boolean hasCachedFile(ThumbnailRecord thumbnail) {
        return thumbnail.getCachePath() != null && new File(thumbnail.getCachePath()).isFile();
    }

    /**
     * Loads a cached thumbnail file, capped to {@link #MAX_THUMBNAIL_DECODE_SIZE} rather than its
     * native on-disk resolution — the grid never displays it any larger than that, so there's no
     * reason to decode (and hold in memory) more pixels than that regardless of how the thumbnail
     * cache file itself was generated. Background-loading (last arg) keeps decoding off the FX
     * thread.
     */
    private Image loadThumbnailImage(ThumbnailRecord thumbnail) {
        File file = new File(thumbnail.getCachePath());
        return new Image(file.toURI()
                             .toString(), MAX_THUMBNAIL_DECODE_SIZE, MAX_THUMBNAIL_DECODE_SIZE, true, true, true);
    }

    private Image loadPreviewImage(byte[] previewBytes) {
        return new Image(new ByteArrayInputStream(previewBytes), MAX_THUMBNAIL_DECODE_SIZE, MAX_THUMBNAIL_DECODE_SIZE, true, true);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String buildPhotoDetailsText(PhotoRecord photo) {
        return getPhotoDetailsText(photo, formatFileSize(photo.getFileSize()));
    }

    private static String formatFileSize(Long bytes) {
        return HumanReadableUtils.size(bytes);
    }

    private static TreeItem<LibraryTreeNode> treeItem(LibraryTreeNode node) {
        return new TreeItem<>(node);
    }

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
        jobManager.submitAiPipelineJob();
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
                    TreeItem<?> item = libraryTreeView.getRoot();
                    return findImportRootPath(libraryTreeView.getRoot(), node.displayName());
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
        DuplicatesController.openSlideshow(photos, startIndex, photoGridListView.getScene(), fxmlLoader, log);
    }
}
