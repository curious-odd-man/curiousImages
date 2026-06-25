package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.config.FxmlLoader;
import com.github.curiousoddman.curious_images.config.FxmlView;
import com.github.curiousoddman.curious_images.dbobj.tables.records.AlbumRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.FolderRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ImportRootRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PersonRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ThumbnailRecord;
import com.github.curiousoddman.curious_images.domain.dedupe.DuplicateDetectionService;
import com.github.curiousoddman.curious_images.domain.search.SearchService;
import com.github.curiousoddman.curious_images.domain.user.prefs.UserPreferencesService;
import com.github.curiousoddman.curious_images.event.AiPipelineCompleteEvent;
import com.github.curiousoddman.curious_images.event.BackgroundProcessEvent;
import com.github.curiousoddman.curious_images.event.InterruptBackgroundProcessEvent;
import com.github.curiousoddman.curious_images.event.LibraryUpdatedEvent;
import com.github.curiousoddman.curious_images.event.RunAiPipelineEvent;
import com.github.curiousoddman.curious_images.model.LoadedFxml;
import com.github.curiousoddman.curious_images.model.TimelineData;
import com.github.curiousoddman.curious_images.model.bundle.RescanBundle;
import com.github.curiousoddman.curious_images.persistence.AlbumPhotoRepository;
import com.github.curiousoddman.curious_images.persistence.AlbumRepository;
import com.github.curiousoddman.curious_images.persistence.FaceRepository;
import com.github.curiousoddman.curious_images.persistence.FolderRepository;
import com.github.curiousoddman.curious_images.persistence.ImportRootRepository;
import com.github.curiousoddman.curious_images.persistence.PersonRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoRepository;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
import com.github.curiousoddman.curious_images.ui.nodes.LibraryTreeCell;
import com.github.curiousoddman.curious_images.ui.nodes.LibraryTreeNode;
import com.github.curiousoddman.curious_images.ui.nodes.LibraryTreeNode.NodeType;
import com.github.curiousoddman.curious_images.ui.nodes.NodePayload;
import com.github.curiousoddman.curious_images.ui.nodes.NodePayload.AlbumPayload;
import com.github.curiousoddman.curious_images.ui.nodes.NodePayload.FolderPayload;
import com.github.curiousoddman.curious_images.ui.nodes.NodePayload.PersonPayload;
import com.github.curiousoddman.curious_images.ui.nodes.NodePayload.TimelinePayload;
import com.github.curiousoddman.curious_images.ui.nodes.NodePayload.UndatedPayload;
import com.github.curiousoddman.curious_images.util.async.DelayedAction;
import javafx.beans.InvalidationListener;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;

import static com.github.curiousoddman.curious_images.ui.controller.screen.DuplicatesController.getPhotoDetailsText;
import static com.github.curiousoddman.curious_images.ui.controller.screen.SlideshowController.getImage;
import static com.sun.javafx.util.Utils.runOnFxThread;

@Lazy
@Slf4j
@Component
@RequiredArgsConstructor
public class LibraryController implements Initializable {
    public static final Font CONSOLAS = new Font("Consolas", 15);

    private static final int SEARCH_TOP_K = 50;

    private final ApplicationEventPublisher eventPublisher;
    private final FxmlLoader                fxmlLoader;
    private final UserPreferencesService    userPreferencesService;
    private final ImportRootRepository      importRootRepository;
    private final FolderRepository          folderRepository;
    private final PhotoRepository           photoRepository;
    private final ThumbnailRepository       thumbnailRepository;
    private final AlbumRepository           albumRepository;
    private final AlbumPhotoRepository      albumPhotoRepository;
    private final PersonRepository          personRepository;
    private final DuplicateDetectionService duplicateDetectionService;
    private final SearchService             searchService;
    private final FaceRepository            faceRepository;

    @FXML
    public SplitPane                 librarySplitPane;
    @FXML
    public TreeView<LibraryTreeNode> libraryTreeView;
    @FXML
    public FlowPane                  photoGridPane;
    @FXML
    public Slider                    thumbnailSizeSlider;
    @FXML
    public Label                     backgroundProgressLabel;
    @FXML
    public Label                     backgroundProgressText;
    @FXML
    public Button                    backgroundProcessCancelButton;
    @FXML
    public ProgressIndicator         backgroundProgressIndicator;
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


    private Image                noImageAvailable;
    private DuplicatesController duplicatesController;

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
        runOnFxThread(() -> {
            int     maxProgress     = event.getMaxProgress();
            int     currentProgress = event.getProgress();
            boolean displayProgress = maxProgress > 0;

            backgroundProgressIndicator.setProgress(
                    displayProgress
                            ? (double) currentProgress / maxProgress
                            : 0f
            );
            backgroundProgressLabel.setText(displayProgress
                    ? currentProgress + " / " + maxProgress
                    : event.getDescription());
            backgroundProgressText.setText(event.getCurrentItem() == null ? "" : event.getCurrentItem());
            boolean terminal = event.getEventType()
                                    .isTerminal();
            backgroundProcessCancelButton.setVisible(!terminal);
            backgroundProgressIndicator.setVisible(!terminal || !displayProgress);
        });
    }

    @FXML
    public void onCancelBackgroundJob(ActionEvent actionEvent) {
        eventPublisher.publishEvent(new InterruptBackgroundProcessEvent(this));
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
        log.info("AI pipeline complete — refreshing Albums and People tree sections");
        Thread t = new Thread(() -> {
            List<TreeItem<LibraryTreeNode>> albumItems  = buildAlbumItems();
            List<TreeItem<LibraryTreeNode>> personItems = buildPersonItems();
            runOnFxThread(() -> {
                // Navigate to the Albums and People roots by position (indices 2 and 3)
                TreeItem<LibraryTreeNode> root = libraryTreeView.getRoot();
                if (root == null) {
                    return;
                }

                ObservableList<TreeItem<LibraryTreeNode>> children = root.getChildren();
                if (children.size() < 4) {
                    return;
                }

                children
                        .get(2)
                        .getChildren()
                        .setAll(albumItems);
                children
                        .get(3)
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
            byYearMonth
                    .computeIfAbsent(day.year(), y -> new LinkedHashMap<>())
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
        List<TreeItem<LibraryTreeNode>> items = new ArrayList<>();
        // Group by type for sub-headers
        Map<String, List<AlbumRecord>> byType = new LinkedHashMap<>();
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
                        album.getName(),
                        new AlbumPayload(album.getId()),
                        nodeType)));
            }
        }
        return items;
    }

    private List<TreeItem<LibraryTreeNode>> buildPersonItems() {
        List<TreeItem<LibraryTreeNode>> items = new ArrayList<>();
        for (PersonRecord person : personRepository.findAll()) {
            String label = person.getName() != null
                    ? person.getName()
                    : "Person #" + person.getId();
            items.add(treeItem(new LibraryTreeNode(
                    label,
                    new PersonPayload(person.getId()),
                    NodeType.PERSON)));
        }
        return items;
    }

    // ── Tree selection ────────────────────────────────────────────────────────

    private void onTreeSelectionChanged(TreeItem<LibraryTreeNode> selectedItem) {
        if (selectedItem == null || selectedItem.getValue() == null) {
            clearPhotoGrid();
            return;
        }
        NodePayload payload = selectedItem.getValue()
                                          .payload();
        if (payload == null) {
            clearPhotoGrid();
            return;
        }
        // Clear any active search when navigating the tree
        clearSearchState();
        switch (payload) {
            case FolderPayload fp -> loadPhotosForFolder(fp.folderId());
            case TimelinePayload tp when tp.month() != null -> loadPhotosForTimeline(tp.year(), tp.month(), tp.day());
            case TimelinePayload ignored -> clearPhotoGrid();
            case UndatedPayload ignored -> loadPhotosUndated();
            case AlbumPayload ap -> loadPhotosForAlbum(ap.albumId());
            case PersonPayload pp -> loadPhotosForPerson(pp.personId());
        }
    }

    // ── Photo loading ─────────────────────────────────────────────────────────

    private void loadPhotosForFolder(long folderId) {
        Thread t = new Thread(() -> {
            List<PhotoRecord> photos = photoRepository.findByFolderId(folderId);
            Map<Long, ThumbnailRecord> thumbs = thumbnailRepository.findByPhotoIds(
                    photos.stream()
                          .map(PhotoRecord::getId)
                          .toList());
            runOnFxThread(() -> populatePhotoGrid(photos, thumbs));
        });
        t.setDaemon(true);
        t.start();
    }

    private void loadPhotosForTimeline(int year, int month, Integer day) {
        Thread t = new Thread(() -> {
            List<PhotoRecord> photos = photoRepository.findByCaptureDate(year, month, day);
            Map<Long, ThumbnailRecord> thumbs = thumbnailRepository.findByPhotoIds(
                    photos.stream()
                          .map(PhotoRecord::getId)
                          .toList());
            runOnFxThread(() -> populatePhotoGrid(photos, thumbs));
        });
        t.setDaemon(true);
        t.start();
    }

    private void loadPhotosUndated() {
        Thread t = new Thread(() -> {
            List<PhotoRecord> photos = photoRepository.findByNullCaptureDate();
            Map<Long, ThumbnailRecord> thumbs = thumbnailRepository.findByPhotoIds(
                    photos.stream()
                          .map(PhotoRecord::getId)
                          .toList());
            runOnFxThread(() -> populatePhotoGrid(photos, thumbs));
        });
        t.setDaemon(true);
        t.start();
    }

    private void loadPhotosForAlbum(long albumId) {
        Thread t = new Thread(() -> {
            List<Long> photoIds = albumPhotoRepository.findPhotoIdsByAlbumId(albumId);
            List<PhotoRecord> photos = photoIds.stream()
                                               .map(id -> photoRepository.findById(id)
                                                                         .orElse(null))
                                               .filter(Objects::nonNull)
                                               .toList();
            Map<Long, ThumbnailRecord> thumbs = thumbnailRepository.findByPhotoIds(photoIds);
            runOnFxThread(() -> populatePhotoGrid(photos, thumbs));
        });
        t.setDaemon(true);
        t.start();
    }

    private void loadPhotosForPerson(long personId) {
        Thread t = new Thread(() -> {
            List<Long> photoIds = new ArrayList<>(
                    new LinkedHashSet<>(
                            faceRepository.findByPersonId(personId)
                                          .stream()
                                          .map(FaceRecord::getPhotoId)
                                          .toList()));
            List<PhotoRecord> photos = photoIds.stream()
                                               .map(id -> photoRepository.findById(id)
                                                                         .orElse(null))
                                               .filter(Objects::nonNull)
                                               .toList();
            Map<Long, ThumbnailRecord> thumbs = thumbnailRepository.findByPhotoIds(photoIds);
            runOnFxThread(() -> populatePhotoGrid(photos, thumbs));
        });
        t.setDaemon(true);
        t.start();
    }

    // ── Search ────────────────────────────────────────────────────────────────

    @FXML
    public void onSearchClicked(ActionEvent event) {
        String query = searchField.getText();
        if (query == null || query.isBlank()) {
            return;
        }

        clearSearchButton.setVisible(true);
        // Deselect the tree while showing search results
        libraryTreeView.getSelectionModel()
                       .clearSelection();

        Thread t = new Thread(() -> {
            try {
                List<Long> photoIds = searchService.semanticSearch(query, SEARCH_TOP_K);
                List<PhotoRecord> photos = photoIds.stream()
                                                   .map(id -> photoRepository.findById(id)
                                                                             .orElse(null))
                                                   .filter(Objects::nonNull)
                                                   .toList();
                Map<Long, ThumbnailRecord> thumbs = thumbnailRepository.findByPhotoIds(photoIds);
                runOnFxThread(() -> {
                    populatePhotoGrid(photos, thumbs);
                    photoCountLabel.setText("Search: " + photos.size() + " results");
                });
            } catch (Exception e) {
                log.error("Semantic search failed for query '{}'", query, e);
                runOnFxThread(() -> photoCountLabel.setText("Search error: " + e.getMessage()));
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

    // ── Photo grid ────────────────────────────────────────────────────────────

    private void populatePhotoGrid(List<PhotoRecord> photos, Map<Long, ThumbnailRecord> thumbnailsByPhotoId) {
        photoGridPane.getChildren()
                     .setAll(
                             photos.stream()
                                   .map(photo -> createPhotoCell(photo, thumbnailsByPhotoId.get(photo.getId())))
                                   .toList());
        photoCountLabel.setText(photos.size() + " photo" + (photos.size() == 1 ? "" : "s"));
    }

    private void clearPhotoGrid() {
        photoGridPane.getChildren()
                     .clear();
        photoCountLabel.setText("");
    }

    private Node createPhotoCell(PhotoRecord photo, ThumbnailRecord thumbnail) {
        ImageView imageView = new ImageView(getImage(thumbnail, noImageAvailable));
        imageView.setPreserveRatio(true);
        imageView.fitWidthProperty()
                 .bind(thumbnailSizeSlider.valueProperty());
        imageView.fitHeightProperty()
                 .bind(thumbnailSizeSlider.valueProperty());

        Label nameLabel = new Label(photo.getFilename());
        nameLabel.setWrapText(true);
        nameLabel.setAlignment(Pos.CENTER);
        nameLabel.setMaxWidth(160.0);

        VBox cell = new VBox(4.0, imageView, nameLabel);
        cell.setAlignment(Pos.TOP_CENTER);
        cell.setPadding(new Insets(6.0));

        Tooltip tooltip = new Tooltip(buildPhotoDetailsText(photo));
        tooltip.setShowDelay(javafx.util.Duration.millis(500));
        tooltip.setFont(CONSOLAS);
        Tooltip.install(cell, tooltip);

        cell.setOnMouseClicked(e -> {
            if (e.getClickCount() == 1) {
                List<PhotoRecord> currentPhotos = photoGridPane.getChildren()
                                                               .stream()
                                                               .map(node -> (PhotoRecord) node.getUserData())
                                                               .filter(Objects::nonNull)
                                                               .toList();
                int idx = currentPhotos.indexOf(photo);
                if (idx >= 0) {
                    openSlideshow(currentPhotos, idx);
                }
            }
        });
        cell.setUserData(photo);
        return cell;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String buildPhotoDetailsText(PhotoRecord photo) {
        return getPhotoDetailsText(photo, formatFileSize(photo.getFileSize()));
    }

    private static String formatFileSize(Long bytes) {
        return humanReadableSize(bytes);
    }

    static String humanReadableSize(Long bytes) {
        if (bytes == null) {
            return "unknown";
        }
        double   size      = bytes;
        String[] units     = {"B", "KB", "MB", "GB"};
        int      unitIndex = 0;
        while (size >= 1024.0 && unitIndex < units.length - 1) {
            size /= 1024.0;
            unitIndex++;
        }
        return String.format("%.1f %s", size, units[unitIndex]);
    }

    private static TreeItem<LibraryTreeNode> treeItem(LibraryTreeNode node) {
        return new TreeItem<>(node);
    }

    @FXML
    @SneakyThrows
    public void onRescanMenuClicked(ActionEvent actionEvent) {
        Stage stage = new Stage();
        Parent root = fxmlLoader.load(FxmlView.RESCAN_MODAL, new RescanBundle("D:\\Programming\\sample-data"))
                                .parent();
        stage.setScene(new Scene(root));
        stage.setTitle("Rescan library");
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initOwner(backgroundProcessCancelButton.getScene()
                                                     .getWindow());
        stage.showAndWait();
    }

    @FXML
    public void onFindDuplicates(ActionEvent event) {
        duplicateDetectionService.start();
    }

    @FXML
    public void onTriggerAiPipeline(ActionEvent event) {
        eventPublisher.publishEvent(new RunAiPipelineEvent(this));
    }

    private void openSlideshow(List<PhotoRecord> photos, int startIndex) {
        DuplicatesController.openSlideshow(photos, startIndex, photoGridPane.getScene(), fxmlLoader, log);
    }
}
