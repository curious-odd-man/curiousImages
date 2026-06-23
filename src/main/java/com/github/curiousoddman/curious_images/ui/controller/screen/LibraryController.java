package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.config.FxmlLoader;
import com.github.curiousoddman.curious_images.config.FxmlView;
import com.github.curiousoddman.curious_images.dbobj.tables.records.FolderRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ImportRootRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ThumbnailRecord;
import com.github.curiousoddman.curious_images.domain.dedupe.DuplicateDetectionService;
import com.github.curiousoddman.curious_images.domain.user.prefs.UserPreferencesService;
import com.github.curiousoddman.curious_images.event.BackgroundProcessEvent;
import com.github.curiousoddman.curious_images.event.InterruptBackgroundProcessEvent;
import com.github.curiousoddman.curious_images.event.LibraryUpdatedEvent;
import com.github.curiousoddman.curious_images.model.LoadedFxml;
import com.github.curiousoddman.curious_images.model.TimelineData;
import com.github.curiousoddman.curious_images.model.bundle.RescanBundle;
import com.github.curiousoddman.curious_images.persistence.FolderRepository;
import com.github.curiousoddman.curious_images.persistence.ImportRootRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoRepository;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
import com.github.curiousoddman.curious_images.ui.nodes.LibraryTreeCell;
import com.github.curiousoddman.curious_images.ui.nodes.LibraryTreeNode;
import com.github.curiousoddman.curious_images.ui.nodes.LibraryTreeNode.NodeType;
import com.github.curiousoddman.curious_images.ui.nodes.NodePayload;
import com.github.curiousoddman.curious_images.ui.nodes.NodePayload.FolderPayload;
import com.github.curiousoddman.curious_images.ui.nodes.NodePayload.TimelinePayload;
import com.github.curiousoddman.curious_images.ui.nodes.NodePayload.UndatedPayload;
import com.github.curiousoddman.curious_images.util.async.DelayedAction;
import javafx.beans.InvalidationListener;
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
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
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
import java.time.Duration;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    private final ApplicationEventPublisher eventPublisher;
    private final FxmlLoader                fxmlLoader;
    private final UserPreferencesService    userPreferencesService;
    private final ImportRootRepository      importRootRepository;
    private final FolderRepository          folderRepository;
    private final PhotoRepository           photoRepository;
    private final ThumbnailRepository       thumbnailRepository;
    private final DuplicateDetectionService duplicateDetectionService;

    @FXML
    public SplitPane                 librarySplitPane;
    @FXML
    public TreeView<LibraryTreeNode> libraryTreeView;
    @FXML
    public FlowPane                  photoGridPane;
    @FXML
    public Slider                    thumbnailSizeSlider;
    @FXML
    public Label                     importProgressLabel;
    @FXML
    public Label                     importCurrentFileLabel;
    @FXML
    public Label                     importElapsedLabel;
    @FXML
    public Button                    backgroundProcessCancelButton;
    @FXML
    public TabPane                   mainTabPane;
    @FXML
    public Tab                       duplicatesTab;

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
            importProgressLabel.setText(event.getMaxProgress() > 0
                    ? event.getProgress() + " / " + event.getMaxProgress()
                    : event.getDescription());
            importCurrentFileLabel.setText(event.getCurrentItem() == null ? "" : event.getCurrentItem());
            long elapsedMs = System.currentTimeMillis() - event.getTimestamp();
            importElapsedLabel.setText(Duration.ofMillis(elapsedMs)
                                               .toString());
            backgroundProcessCancelButton.setVisible(!event.getEventType()
                                                           .isTerminal());
        });
    }

    @FXML
    public void onCancelBackgroundJob(ActionEvent actionEvent) {
        eventPublisher.publishEvent(new InterruptBackgroundProcessEvent(this));
    }

    // ── Tree building ─────────────────────────────────────────────────────────

    @SneakyThrows
    @EventListener
    public void onLibraryDataUpdated(LibraryUpdatedEvent event) {
        log.info("Rebuilding library tree");
        Thread t = new Thread(() -> {
            List<TreeItem<LibraryTreeNode>> folderItems   = buildImportRootItems();
            List<TreeItem<LibraryTreeNode>> timelineItems = buildTimelineItems();

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

                TreeItem<LibraryTreeNode> invisibleRoot = new TreeItem<>();
                invisibleRoot.getChildren()
                             .setAll(foldersRoot, timelineRoot);
                libraryTreeView.setRoot(invisibleRoot);
            });
        });
        t.setDaemon(true);
        t.start();
    }

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

        // Group days by year → month
        // LinkedHashMap preserves the DB ordering (already sorted by year, month, day)
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
        switch (payload) {
            case FolderPayload fp -> loadPhotosForFolder(fp.folderId());
            case TimelinePayload tp when
                    tp.month() != null -> loadPhotosForTimeline(tp.year(), tp.month(), tp.day());
            case TimelinePayload ignored -> clearPhotoGrid(); // year-only: no grid
            case UndatedPayload ignored -> loadPhotosUndated();
        }
    }

    // ── Photo loading ─────────────────────────────────────────────────────────

    private void loadPhotosForFolder(long folderId) {
        Thread t = new Thread(() -> {
            List<PhotoRecord> photos = photoRepository.findByFolderId(folderId);
            Map<Long, ThumbnailRecord> thumbnails = thumbnailRepository.findByPhotoIds(
                    photos.stream()
                          .map(PhotoRecord::getId)
                          .toList());
            runOnFxThread(() -> populatePhotoGrid(photos, thumbnails));
        });
        t.setDaemon(true);
        t.start();
    }

    private void loadPhotosForTimeline(int year, int month, Integer day) {
        Thread t = new Thread(() -> {
            List<PhotoRecord> photos = photoRepository.findByCaptureDate(year, month, day);
            Map<Long, ThumbnailRecord> thumbnails = thumbnailRepository.findByPhotoIds(
                    photos.stream()
                          .map(PhotoRecord::getId)
                          .toList());
            runOnFxThread(() -> populatePhotoGrid(photos, thumbnails));
        });
        t.setDaemon(true);
        t.start();
    }

    private void loadPhotosUndated() {
        Thread t = new Thread(() -> {
            List<PhotoRecord> photos = photoRepository.findByNullCaptureDate();
            Map<Long, ThumbnailRecord> thumbnails = thumbnailRepository.findByPhotoIds(
                    photos.stream()
                          .map(PhotoRecord::getId)
                          .toList());
            runOnFxThread(() -> populatePhotoGrid(photos, thumbnails));
        });
        t.setDaemon(true);
        t.start();
    }

    // ── Photo grid ────────────────────────────────────────────────────────────

    private void populatePhotoGrid(List<PhotoRecord> photos, Map<Long, ThumbnailRecord> thumbnailsByPhotoId) {
        photoGridPane.getChildren()
                     .setAll(
                             photos.stream()
                                   .map(photo -> createPhotoCell(photo, thumbnailsByPhotoId.get(photo.getId())))
                                   .toList());
    }

    private void clearPhotoGrid() {
        photoGridPane.getChildren()
                     .clear();
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
        Parent root = fxmlLoader.load(FxmlView.RESCAN_MODAL, new RescanBundle("D:\\My Pictures"))
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

    private void openSlideshow(List<PhotoRecord> photos, int startIndex) {
        DuplicatesController.openSlideshow(photos, startIndex, photoGridPane.getScene(), fxmlLoader, log);
    }
}