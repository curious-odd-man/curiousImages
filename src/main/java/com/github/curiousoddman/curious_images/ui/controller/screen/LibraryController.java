package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.config.FxmlLoader;
import com.github.curiousoddman.curious_images.config.FxmlView;
import com.github.curiousoddman.curious_images.dbobj.tables.records.FolderRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ImportRootRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ThumbnailRecord;
import com.github.curiousoddman.curious_images.domain.dedupe.DuplicateDetectionService;
import com.github.curiousoddman.curious_images.domain.dedupe.DuplicateResolutionService;
import com.github.curiousoddman.curious_images.domain.user.prefs.UserPreferencesService;
import com.github.curiousoddman.curious_images.event.BackgroundProcessEvent;
import com.github.curiousoddman.curious_images.event.InterruptBackgroundProcessEvent;
import com.github.curiousoddman.curious_images.event.LibraryUpdatedEvent;
import com.github.curiousoddman.curious_images.model.DuplicateGroupView;
import com.github.curiousoddman.curious_images.model.LoadedFxml;
import com.github.curiousoddman.curious_images.model.PhotoWithThumbnail;
import com.github.curiousoddman.curious_images.model.bundle.RescanBundle;
import com.github.curiousoddman.curious_images.model.bundle.SlideshowBundle;
import com.github.curiousoddman.curious_images.persistence.DuplicateGroupRepository;
import com.github.curiousoddman.curious_images.persistence.FolderRepository;
import com.github.curiousoddman.curious_images.persistence.ImportRootRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoRepository;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
import com.github.curiousoddman.curious_images.ui.nodes.LibraryTreeNode;
import com.github.curiousoddman.curious_images.ui.styles.CssClasses;
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
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.github.curiousoddman.curious_images.ui.controller.screen.SlideshowController.getImage;
import static com.sun.javafx.util.Utils.runOnFxThread;

@Lazy
@Slf4j
@Component
@RequiredArgsConstructor
public class LibraryController implements Initializable {
    public static final Font CONSOLAS = new Font("Consolas", 15);
    private final ApplicationEventPublisher eventPublisher;
    private final FxmlLoader fxmlLoader;
    private final UserPreferencesService userPreferencesService;
    private final ImportRootRepository importRootRepository;
    private final FolderRepository folderRepository;
    private final PhotoRepository photoRepository;
    private final ThumbnailRepository thumbnailRepository;
    private final DuplicateDetectionService duplicateDetectionService;
    private final DuplicateGroupRepository duplicateGroupRepository;
    private final DuplicateResolutionService duplicateResolutionService;

    @FXML
    public SplitPane librarySplitPane;

    // Library tree (left) and photo grid (right).
    @FXML
    public TreeView<LibraryTreeNode> libraryTreeView;
    @FXML
    public FlowPane photoGridPane;
    @FXML
    public Slider thumbnailSizeSlider;

    // Import status bar (§11).
    @FXML
    public Label importProgressLabel;
    @FXML
    public Label importCurrentFileLabel;
    @FXML
    public Label importElapsedLabel;
    @FXML
    public Button backgroundProcessCancelButton;

    // Duplicates review.
    @FXML
    public TabPane mainTabPane;
    @FXML
    public Tab duplicatesTab;
    @FXML
    public Accordion duplicatesAccordion;
    @FXML
    public Button keepSelectedButton;
    @FXML
    public Button deleteSelectedButton;

    /**
     * Shown in place of a thumbnail when no {@code THUMBNAIL} row/file exists yet for a photo.
     */
    private Image noImageAvailable;

    @Override
    @SneakyThrows
    public void initialize(URL location, ResourceBundle resources) {
        noImageAvailable = new Image(getClass().getResourceAsStream("/img/noimage.png"));
        libraryTreeView.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldItem, newItem) -> onTreeSelectionChanged(newItem));
        onLibraryDataUpdated(null);

        mainTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == duplicatesTab) {
                loadDuplicatesTab();
            }
        });
        duplicatesAccordion.expandedPaneProperty().addListener((obs, oldPane, newPane) -> updateActionButtonsState());
        keepSelectedButton.setOnMouseEntered(e -> previewAction(true));
        keepSelectedButton.setOnMouseExited(e -> clearPreview());
        deleteSelectedButton.setOnMouseEntered(e -> previewAction(false));
        deleteSelectedButton.setOnMouseExited(e -> clearPreview());
    }

    public void setUserPrefs(Stage primaryStage) {
        userPreferencesService.restoreWindowState(primaryStage);
        DelayedAction delayedSaveWindowSize = new DelayedAction(500, TimeUnit.MILLISECONDS);
        InvalidationListener invalidationListener = o ->
                delayedSaveWindowSize.reSchedule(() ->
                        userPreferencesService.saveWindowState(primaryStage));

        primaryStage.widthProperty().addListener(invalidationListener);
        primaryStage.heightProperty().addListener(invalidationListener);
        primaryStage.xProperty().addListener(invalidationListener);
        primaryStage.yProperty().addListener(invalidationListener);
        primaryStage.maximizedProperty().addListener(invalidationListener);

        librarySplitPane.setDividerPositions(userPreferencesService.getDividerPositions());
        DelayedAction delayedSaveDividerPosition = new DelayedAction(500, TimeUnit.MILLISECONDS);

        InvalidationListener splitPanePositionListener = o ->
                delayedSaveDividerPosition.reSchedule(() ->
                        userPreferencesService.saveSplitPositions(librarySplitPane.getDividerPositions()));

        librarySplitPane.getDividers().getFirst().positionProperty().addListener(splitPanePositionListener);
    }

    @EventListener
    public void onBackgroundProcessEvent(BackgroundProcessEvent event) {
        runOnFxThread(() -> {
            importProgressLabel.setText(event.getMaxProgress() > 0
                    ? event.getProgress() + " / " + event.getMaxProgress()
                    : event.getDescription());
            importCurrentFileLabel.setText(event.getCurrentItem() == null ? "" : event.getCurrentItem());
            long elapsedMs = System.currentTimeMillis() - event.getTimestamp();
            importElapsedLabel.setText(Duration.ofMillis(elapsedMs).toString());
            backgroundProcessCancelButton.setVisible(!event.getEventType().isTerminal());
        });
    }

    @FXML
    public void onCancelBackgroundJob(ActionEvent actionEvent) {
        eventPublisher.publishEvent(new InterruptBackgroundProcessEvent(this));
    }

    @SneakyThrows
    @EventListener
    public void onLibraryDataUpdated(LibraryUpdatedEvent event) {
        log.info("Loading library tree in separate thread");
        Thread t = new Thread(() -> {
            List<TreeItem<LibraryTreeNode>> rootItems = buildImportRootItems();
            runOnFxThread(() -> {
                TreeItem<LibraryTreeNode> invisibleRoot = new TreeItem<>();
                invisibleRoot.getChildren().setAll(rootItems);
                libraryTreeView.setRoot(invisibleRoot);
            });
        });
        t.setDaemon(true);
        t.start();
    }

    @FXML
    public void onFindDuplicates(ActionEvent event) {
        duplicateDetectionService.start();
    }

    private List<TreeItem<LibraryTreeNode>> buildImportRootItems() {
        List<TreeItem<LibraryTreeNode>> rootItems = new ArrayList<>();
        for (ImportRootRecord importRoot : importRootRepository.findAll()) {
            FolderRecord rootFolder = folderRepository.findRootFolder(importRoot.getId()).orElse(null);
            Long rootFolderId = rootFolder == null ? null : rootFolder.getId();
            TreeItem<LibraryTreeNode> folderRootItem = new TreeItem<>(
                    new LibraryTreeNode(importRoot.getPath(), rootFolderId, LibraryTreeNode.NodeType.IMPORT_ROOT));
            if (rootFolderId != null) {
                folderRootItem.getChildren().addAll(buildFolderItems(rootFolderId));
            }
            rootItems.add(folderRootItem);
        }
        return rootItems;
    }

    private List<TreeItem<LibraryTreeNode>> buildFolderItems(long parentFolderId) {
        List<TreeItem<LibraryTreeNode>> items = new ArrayList<>();
        for (FolderRecord folder : folderRepository.findChildren(parentFolderId)) {
            TreeItem<LibraryTreeNode> item = new TreeItem<>(
                    new LibraryTreeNode(folder.getName(), folder.getId(), LibraryTreeNode.NodeType.FOLDER));
            item.getChildren().addAll(buildFolderItems(folder.getId()));
            items.add(item);
        }
        return items;
    }

    private void onTreeSelectionChanged(TreeItem<LibraryTreeNode> selectedItem) {
        if (selectedItem == null || selectedItem.getValue() == null || selectedItem.getValue().folderId() == null) {
            clearPhotoGrid();
            return;
        }
        loadPhotosForFolder(selectedItem.getValue().folderId());
    }

    /**
     * Loads photos + their thumbnail rows for one folder on a background thread.
     */
    private void loadPhotosForFolder(long folderId) {
        Thread t = new Thread(() -> {
            List<PhotoRecord> photos = photoRepository.findByFolderId(folderId);
            Map<Long, ThumbnailRecord> thumbnailsByPhotoId = thumbnailRepository.findByPhotoIds(
                    photos.stream().map(PhotoRecord::getId).toList());
            runOnFxThread(() -> populatePhotoGrid(photos, thumbnailsByPhotoId));
        });
        t.setDaemon(true);
        t.start();
    }

    private void populatePhotoGrid(List<PhotoRecord> photos, Map<Long, ThumbnailRecord> thumbnailsByPhotoId) {
        photoGridPane.getChildren().setAll(
                photos.stream()
                        .map(photo -> createPhotoCell(photo, thumbnailsByPhotoId.get(photo.getId())))
                        .toList());
    }

    private void clearPhotoGrid() {
        photoGridPane.getChildren().clear();
    }

    /**
     * One grid cell: thumbnail (or the no-image placeholder) + filename underneath, with a hover
     * tooltip (0.5s delay) listing everything the schema currently has on the photo.
     */
    private Node createPhotoCell(PhotoRecord photo, ThumbnailRecord thumbnail) {
        ImageView imageView = new ImageView(loadThumbnailImage(thumbnail));
        imageView.setPreserveRatio(true);
        imageView.fitWidthProperty().bind(thumbnailSizeSlider.valueProperty());
        imageView.fitHeightProperty().bind(thumbnailSizeSlider.valueProperty());

        Label nameLabel = new Label(photo.getFilename());
        nameLabel.setWrapText(true);
        nameLabel.setAlignment(Pos.CENTER);
        nameLabel.setMaxWidth(160.0);

        VBox cell = new VBox(4.0, imageView, nameLabel);
        cell.setAlignment(Pos.TOP_CENTER);
        cell.setPadding(new Insets(6.0));

        Tooltip tooltip = new Tooltip(buildPhotoDetailsText(photo));
        tooltip.setShowDelay(javafx.util.Duration.millis(500));
        Tooltip.install(cell, tooltip);

        // Capture the photo list from the grid at click time (already loaded into the pane).
        cell.setOnMouseClicked(e -> {
            if (e.getClickCount() == 1) {
                List<PhotoRecord> currentPhotos = photoGridPane.getChildren().stream()
                        .map(node -> (PhotoRecord) node.getUserData())
                        .filter(Objects::nonNull)
                        .toList();
                int idx = currentPhotos.indexOf(photo);
                if (idx >= 0) {
                    openSlideshow(currentPhotos, idx);
                }
            }
        });
        cell.setUserData(photo);   // ← tag each cell so the list above can recover it

        return cell;
    }

    private Image loadThumbnailImage(ThumbnailRecord thumbnail) {
        return getImage(thumbnail, noImageAvailable);
    }

    private String buildPhotoDetailsText(PhotoRecord photo) {
        StringBuilder sb = new StringBuilder();
        sb.append(photo.getFilename()).append('\n');
        sb.append(photo.getAbsolutePath()).append('\n');
        sb.append("Extension:  ").append(photo.getExtension() == null ? "—" : photo.getExtension()).append('\n');
        sb.append("Size:       ").append(formatFileSize(photo.getFileSize())).append('\n');
        if (photo.getImageWidth() != null && photo.getImageHeight() != null) {
            sb.append("Dimensions: ").append(photo.getImageWidth()).append(" x ").append(photo.getImageHeight()).append('\n');
        }
        if (photo.getCaptureDate() != null) {
            sb.append("Captured:    ").append(photo.getCaptureDate());
            if (photo.getCaptureDateSource() != null) {
                sb.append(" (").append(photo.getCaptureDateSource()).append(')');
            }
            sb.append('\n');
        }
        if (photo.getImportedAt() != null) {
            sb.append("Imported:    ").append(photo.getImportedAt()).append('\n');
        }
        if (photo.getLastSeenAt() != null) {
            sb.append("Last seen:    ").append(photo.getLastSeenAt());
        }
        return sb.toString().strip();
    }

    private static String formatFileSize(Long bytes) {
        if (bytes == null) {
            return "unknown";
        }
        double size = bytes;
        String[] units = {"B", "KB", "MB", "GB"};
        int unitIndex = 0;
        while (size >= 1024.0 && unitIndex < units.length - 1) {
            size /= 1024.0;
            unitIndex++;
        }
        return String.format("%.1f %s", size, units[unitIndex]);
    }

    @FXML
    @SneakyThrows
    public void onRescanMenuClicked(ActionEvent actionEvent) {
        Stage stage = new Stage();
        Parent root = fxmlLoader.load(FxmlView.RESCAN_MODAL, new RescanBundle("D:\\My Pictures")).parent();
        stage.setScene(new Scene(root));
        stage.setTitle("Rescan library");
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initOwner(backgroundProcessCancelButton.getScene().getWindow());
        stage.showAndWait();
    }

    private void openSlideshow(List<PhotoRecord> photos, int startIndex) {
        try {
            Stage stage = new Stage();
            stage.setTitle("Slideshow");
            stage.initStyle(StageStyle.UNDECORATED);
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.initOwner(photoGridPane.getScene().getWindow());

            LoadedFxml<SlideshowController> loaded = fxmlLoader.load(FxmlView.SLIDESHOW, null);
            SlideshowController controller = loaded.controller();
            controller.initSlideshow(new SlideshowBundle(photos, startIndex));

            Scene scene = new Scene(loaded.parent());
            stage.setScene(scene);
            stage.setMaximized(true);
            stage.show();
        } catch (Exception ex) {
            log.error("Failed to open slideshow", ex);
        }
    }

    // ----------------------------------------------------------------------------------------
    // Duplicates tab
    // ----------------------------------------------------------------------------------------

    /**
     * One photo's accordion-pane cell: the live checkbox + the container node that gets the
     * red/green preview border, kept together so hover preview and button actions can read/style
     * them without walking the scene graph.
     */
    private record DuplicateCell(PhotoRecord photo, CheckBox checkBox, VBox container) {
    }

    /**
     * Stashed as a {@code TitledPane}'s user data so actions know which group + cells are "open".
     */
    private record PaneData(long groupId, List<DuplicateCell> cells) {
    }

    /**
     * Reloads the Duplicates tab from the DB. Called whenever the tab is selected, and again
     * after a keep/delete action completes so the view reflects what's left.
     */
    private void loadDuplicatesTab() {
        Thread t = new Thread(() -> {
            List<DuplicateGroupView> groups = duplicateGroupRepository.findAllGroupsWithMembers();
            runOnFxThread(() -> populateDuplicatesAccordion(groups));
        });
        t.setDaemon(true);
        t.start();
    }

    private void populateDuplicatesAccordion(List<DuplicateGroupView> groups) {
        ObservableList<TitledPane> panes = duplicatesAccordion.getPanes();
        panes.setAll(groups.stream().map(this::buildDuplicateGroupPane).toList());
        if (!panes.isEmpty()) {
            duplicatesAccordion.setExpandedPane(panes.getFirst());
        }
        updateActionButtonsState();
    }

    private TitledPane buildDuplicateGroupPane(DuplicateGroupView group) {
        List<DuplicateCell> cells = new ArrayList<>();
        FlowPane cellsPane = new FlowPane(10.0, 10.0);
        cellsPane.setPadding(new Insets(10.0));
        for (PhotoWithThumbnail pwt : group.photos()) {
            DuplicateCell cell = createDuplicateCell(cells, pwt.photo(), pwt.thumbnail());
            cells.add(cell);
            VBox container = cell.container();
            cellsPane.getChildren().add(container);
        }

        // Wire slideshow click for each cell in this duplicate group
        List<PhotoRecord> groupPhotos = cells.stream().map(DuplicateCell::photo).toList();
        for (int i = 0; i < cells.size(); i++) {
            final int idx = i;
            cells.get(i).container().setOnMouseClicked(e -> {
                if (e.getClickCount() == 1) {
                    openSlideshow(groupPhotos, idx);
                }
            });
        }

        TitledPane pane = new TitledPane(buildGroupTitle(group), cellsPane);
        pane.setUserData(new PaneData(group.groupId(), cells));
        return pane;
    }

    private String buildGroupTitle(DuplicateGroupView group) {
        String ext = group.extension() == null ? "—" : group.extension();
        return "%s · %d photos".formatted(ext, group.photos().size());
    }

    /**
     * One photo within a duplicate group: thumbnail, full metadata shown inline (not a hover
     * tooltip — the whole point is comparing photos side by side), and a "keep" checkbox.
     */
    private DuplicateCell createDuplicateCell(List<DuplicateCell> cells, PhotoRecord photo, ThumbnailRecord thumbnail) {
        ImageView imageView = new ImageView(loadThumbnailImage(thumbnail));
        imageView.setPreserveRatio(true);
        imageView.setFitWidth(160.0);
        imageView.setFitHeight(160.0);

        Label infoLabel = new Label(buildPhotoDetailsText(photo));
        infoLabel.setWrapText(true);
        infoLabel.setMaxWidth(220.0);
        infoLabel.setFont(CONSOLAS);

        CheckBox checkBox = new CheckBox("Keep");

        VBox container = new VBox(6.0, imageView, infoLabel, checkBox);
        container.setAlignment(Pos.TOP_CENTER);
        container.setPadding(new Insets(8.0));
        container.setMaxWidth(240.0);

        DuplicateCell cell = new DuplicateCell(photo, checkBox, container);
        checkBox.selectedProperty().addListener((obs, was, isNow) -> updateActionButtonsState());
        return cell;
    }

    private PaneData activePaneData() {
        TitledPane expanded = duplicatesAccordion.getExpandedPane();
        return expanded == null ? null : (PaneData) expanded.getUserData();
    }

    /**
     * Both buttons act on the currently expanded pane only, and are disabled with nothing checked.
     */
    private void updateActionButtonsState() {
        PaneData active = activePaneData();
        boolean anyChecked = active != null && active.cells().stream().anyMatch(c -> c.checkBox().isSelected());
        keepSelectedButton.setDisable(!anyChecked);
        deleteSelectedButton.setDisable(!anyChecked);
    }

    /**
     * Hover preview: "Keep Selected" keeps the checked photos (green) and drops the unchecked
     * ones (red); "Delete Selected" is the exact mirror image.
     */
    private void previewAction(boolean keepButtonHovered) {
        PaneData active = activePaneData();
        if (active == null) {
            return;
        }
        for (DuplicateCell cell : active.cells()) {
            boolean checked = cell.checkBox().isSelected();
            boolean willBeKept = keepButtonHovered == checked;
            ObservableList<String> styleClass = cell.container().getStyleClass();
            styleClass.add(willBeKept ? CssClasses.KEEP_PREVIEW : CssClasses.DROP_PREVIEW);
            styleClass.remove(willBeKept ? CssClasses.DROP_PREVIEW : CssClasses.KEEP_PREVIEW);
        }
    }

    private void clearPreview() {
        PaneData active = activePaneData();
        if (active == null) {
            return;
        }
        for (DuplicateCell cell : active.cells()) {
            cell.container().getStyleClass().clear();
        }
    }

    @FXML
    public void onKeepSelectedClicked(ActionEvent event) {
        resolveActivePane(true);
    }

    @FXML
    public void onDeleteSelectedClicked(ActionEvent event) {
        resolveActivePane(false);
    }

    /**
     * @param keepChecked {@code true} for "Keep Selected" (drop the unchecked photos),
     *                    {@code false} for "Delete Selected" (drop the checked photos)
     */
    private void resolveActivePane(boolean keepChecked) {
        PaneData active = activePaneData();
        if (active == null) {
            return;
        }
        List<PhotoRecord> toDrop = active.cells().stream()
                .filter(c -> c.checkBox().isSelected() != keepChecked)
                .map(DuplicateCell::photo)
                .toList();
        if (toDrop.isEmpty()) {
            return;
        }

        keepSelectedButton.setDisable(true);
        deleteSelectedButton.setDisable(true);
        Thread t = new Thread(() -> {
            DuplicateResolutionService.Result result = duplicateResolutionService.resolve(active.groupId(), toDrop);
            runOnFxThread(() -> {
                if (!result.failures().isEmpty()) {
                    showResolutionFailures(result.failures());
                }
                loadDuplicatesTab();
            });
        });
        t.setDaemon(true);
        t.start();
    }

    private void showResolutionFailures(List<DuplicateResolutionService.Failure> failures) {
        StringBuilder sb = new StringBuilder();
        for (DuplicateResolutionService.Failure failure : failures) {
            sb.append("• ").append(failure.photo().getFilename()).append(" — ").append(failure.reason()).append('\n');
        }
        Alert alert = new Alert(Alert.AlertType.WARNING, sb.toString().strip(), ButtonType.OK);
        alert.setHeaderText("Some photos couldn't be moved to the recycle bin and were left in place");
        alert.showAndWait();
    }
}
