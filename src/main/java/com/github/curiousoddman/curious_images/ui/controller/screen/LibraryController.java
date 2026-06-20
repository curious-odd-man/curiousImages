package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.config.FxmlLoader;
import com.github.curiousoddman.curious_images.config.FxmlView;
import com.github.curiousoddman.curious_images.dbobj.tables.records.FolderRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ImportRootRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ThumbnailRecord;
import com.github.curiousoddman.curious_images.domain.user.prefs.UserPreferencesService;
import com.github.curiousoddman.curious_images.event.BackgroundProcessEvent;
import com.github.curiousoddman.curious_images.event.InterruptBackgroundProcessEvent;
import com.github.curiousoddman.curious_images.model.bundle.RescanBundle;
import com.github.curiousoddman.curious_images.persistence.FolderRepository;
import com.github.curiousoddman.curious_images.persistence.ImportRootRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoRepository;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
import com.github.curiousoddman.curious_images.ui.nodes.LibraryTreeNode;
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
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
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

import static com.github.curiousoddman.curious_images.domain.imports.ImportService.IMPORT_SCAN;
import static com.sun.javafx.util.Utils.runOnFxThread;

@Lazy
@Slf4j
@Component
@RequiredArgsConstructor
public class LibraryController implements Initializable {
    private final ApplicationEventPublisher eventPublisher;
    private final FxmlLoader fxmlLoader;
    private final UserPreferencesService userPreferencesService;
    private final ImportRootRepository importRootRepository;
    private final FolderRepository folderRepository;
    private final PhotoRepository photoRepository;
    private final ThumbnailRepository thumbnailRepository;

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
    public Button importCancelButton;

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
        onLibraryDataUpdated();
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
        librarySplitPane.getDividers().getLast().positionProperty().addListener(splitPanePositionListener);
    }

    @EventListener
    public void onBackgroundProcessEvent(BackgroundProcessEvent event) {
        if (!event.getProcessName().equals(IMPORT_SCAN)) {
            return;
        }
        runOnFxThread(() -> {
            importProgressLabel.setText(event.getMaxProgress() > 0
                    ? event.getProgress() + " / " + event.getMaxProgress()
                    : event.getDescription());
            importCurrentFileLabel.setText(event.getCurrentItem() == null ? "" : event.getCurrentItem());
            long elapsedMs = System.currentTimeMillis() - event.getTimestamp();
            importElapsedLabel.setText(Duration.ofMillis(elapsedMs).toString());
            importCancelButton.setVisible(!event.getEventType().isTerminal());
        });
    }

    @FXML
    public void onCancelImport(ActionEvent actionEvent) {
        eventPublisher.publishEvent(new InterruptBackgroundProcessEvent(this));
    }

    @SneakyThrows
    private void onLibraryDataUpdated() {
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

        return cell;
    }

    private Image loadThumbnailImage(ThumbnailRecord thumbnail) {
        if (thumbnail != null && thumbnail.getCachePath() != null) {
            File file = new File(thumbnail.getCachePath());
            if (file.isFile()) {
                // backgroundLoading=true: decode happens off the FX thread.
                return new Image(file.toURI().toString(), 0, 0, true, true, true);
            }
        }
        return noImageAvailable;
    }

    private String buildPhotoDetailsText(PhotoRecord photo) {
        StringBuilder sb = new StringBuilder();
        sb.append(photo.getFilename()).append('\n');
        sb.append(photo.getAbsolutePath()).append('\n');
        sb.append("Extension: ").append(photo.getExtension() == null ? "—" : photo.getExtension()).append('\n');
        sb.append("Size: ").append(formatFileSize(photo.getFileSize())).append('\n');
        if (photo.getImageWidth() != null && photo.getImageHeight() != null) {
            sb.append("Dimensions: ").append(photo.getImageWidth()).append(" x ").append(photo.getImageHeight()).append('\n');
        }
        if (photo.getCaptureDate() != null) {
            sb.append("Captured: ").append(photo.getCaptureDate());
            if (photo.getCaptureDateSource() != null) {
                sb.append(" (").append(photo.getCaptureDateSource()).append(')');
            }
            sb.append('\n');
        }
        if (photo.getImportedAt() != null) {
            sb.append("Imported: ").append(photo.getImportedAt()).append('\n');
        }
        if (photo.getLastSeenAt() != null) {
            sb.append("Last seen: ").append(photo.getLastSeenAt());
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
        stage.initOwner(importCancelButton.getScene().getWindow());
        stage.showAndWait();
    }
}
