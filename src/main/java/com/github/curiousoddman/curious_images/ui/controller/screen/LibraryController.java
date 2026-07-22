package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.domain.ai.ModelDownloadJob;
import com.github.curiousoddman.curious_images.domain.ai.ModelPaths;
import com.github.curiousoddman.curious_images.domain.search.SearchAutocompleteManager;
import com.github.curiousoddman.curious_images.domain.search.SearchService;
import com.github.curiousoddman.curious_images.domain.user.prefs.UserPreferencesService;
import com.github.curiousoddman.curious_images.event.model.BackgroundProcessEvent;
import com.github.curiousoddman.curious_images.event.payload.BackgroundProcessPayload;
import com.github.curiousoddman.curious_images.event.types.BackgroundProcessEventType;
import com.github.curiousoddman.curious_images.model.ImageDetails;
import com.github.curiousoddman.curious_images.model.LoadedFxml;
import com.github.curiousoddman.curious_images.model.UiElement;
import com.github.curiousoddman.curious_images.model.bundle.AddFilesBundle;
import com.github.curiousoddman.curious_images.model.bundle.PhotoCellResources;
import com.github.curiousoddman.curious_images.model.bundle.RescanBundle;
import com.github.curiousoddman.curious_images.persistence.PhotoRepository;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.FxmlView;
import com.github.curiousoddman.curious_images.ui.controller.custom.RightPanelController;
import com.github.curiousoddman.curious_images.ui.controller.services.NotificationsService;
import com.github.curiousoddman.curious_images.ui.controller.custom.PhotoGridController;
import com.github.curiousoddman.curious_images.ui.controller.services.LibraryViewManager;
import com.github.curiousoddman.curious_images.ui.controller.services.PhotoGridManager;
import com.github.curiousoddman.curious_images.ui.controller.services.TreeManager;
import com.github.curiousoddman.curious_images.ui.nodes.LibraryTreeCell;
import com.github.curiousoddman.curious_images.ui.nodes.LibraryTreeNode;
import com.github.curiousoddman.curious_images.ui.nodes.LibraryTreeNode.NodeType;
import com.github.curiousoddman.curious_images.ui.nodes.NodePayload;
import com.github.curiousoddman.curious_images.ui.nodes.NodePayload.AlbumPayload;
import com.github.curiousoddman.curious_images.ui.nodes.NodePayload.FolderPayload;
import com.github.curiousoddman.curious_images.ui.nodes.NodePayload.PersonPayload;
import com.github.curiousoddman.curious_images.ui.nodes.NodePayload.TimelinePayload;
import com.github.curiousoddman.curious_images.ui.nodes.NodePayload.UndatedPayload;
import com.github.curiousoddman.curious_images.ui.util.AlertHelper;
import com.github.curiousoddman.curious_images.util.async.DelayedAction;
import com.github.curiousoddman.curious_images.util.async.jobs.JobManager;
import javafx.beans.InvalidationListener;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;

import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static com.sun.javafx.util.Utils.runOnFxThread;
import static javafx.scene.control.ProgressIndicator.INDETERMINATE_PROGRESS;

@Lazy
@Slf4j
@Component
@RequiredArgsConstructor
public class LibraryController implements Initializable {
    private static final int SEARCH_TOP_K = 50;

    private final FxmlLoader             fxmlLoader;
    private final UserPreferencesService userPreferencesService;
    private final PhotoRepository        photoRepository;
    private final SearchService          searchService;
    private final JobManager             jobManager;
    private final ModelPaths             modelPaths;
    private final TreeManager                treeManager;
    private final PhotoGridManager           photoGridManager;
    private final LibraryViewManager         libraryViewManager;
    private final NotificationsService      notificationsService;
    private final SearchAutocompleteManager searchAutocompleteManager;

    // ── FXML nodes ────────────────────────────────────────────────────────────

    @FXML
    public SplitPane                 librarySplitPane;
    @FXML
    public TreeView<LibraryTreeNode> libraryTreeView;
    @FXML
    public TextField                 searchField;
    @FXML
    public Button                    searchButton;
    @FXML
    public Button                    clearSearchButton;
    @FXML
    public StackPane                 contentStack;
    @FXML
    public BorderPane                photoGridContainer;
    @FXML
    public AnchorPane                personDetailContainer;
    @FXML
    public AnchorPane                duplicatesContainer;
    @FXML
    public AnchorPane                folderDuplicatesContainer;
    @FXML
    public HBox                      backgroundProgressContainer;
    @FXML
    public Label                     backgroundProcessTitleLabel;
    @FXML
    public Button                    backgroundProcessCancelButton;
    @FXML
    public StackPane                 backgroundProgressBarContainer;
    @FXML
    public ProgressBar               backgroundProgressBar;
    @FXML
    public Label                     backgroundProgressLabel;
    @FXML
    public Label                     backgroundProgressDescription;
    @FXML
    public Menu                      notificationsMenu;
    @FXML
    public BorderPane                rootBorderPane;

    private PersonDetailController personDetailController;
    private PhotoGridController    photoGridController;
    private RightPanelController   rightPanelController;

    private volatile boolean autoStartAiPipelineAfterModelDownload = false;

    @Override
    @SneakyThrows
    public void initialize(URL location, ResourceBundle resources) {
        notificationsService.initialize(notificationsMenu);
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

        // @person / #tag suggestion popup. Installed as an event filter internally, so it
        // intercepts Down/Up/Ctrl+Enter/Ctrl+Space/Escape ahead of the plain-Enter handler above
        // while its popup is open, and otherwise gets out of the way.
        searchAutocompleteManager.initialize(searchField);

        LoadedFxml<PhotoGridController> photoGridLoaded = fxmlLoader.load(FxmlView.PHOTO_GRID, new PhotoCellResources(this::onShowRightPanel));
        photoGridController = photoGridLoaded.controller();
        photoGridContainer.setCenter(photoGridLoaded.parent());

        photoGridManager.initialize(photoGridController);
        treeManager.onLibraryDataUpdated(null);

        LoadedFxml<DuplicatesController>       duplicatesLoaded       = fxmlLoader.loadFxmlAndAttachToParent(duplicatesContainer, FxmlView.DUPLICATES);
        LoadedFxml<FolderDuplicatesController> folderDuplicatesLoaded = fxmlLoader.loadFxmlAndAttachToParent(folderDuplicatesContainer, FxmlView.FOLDER_DUPLICATES);
        checkModelsAndPromptDownload();

        libraryViewManager.initialize(
                new UiElement<>(photoGridContainer, photoGridController),
                new UiElement<>(duplicatesContainer, duplicatesLoaded.controller()),
                new UiElement<>(folderDuplicatesContainer, folderDuplicatesLoaded.controller()),
                new UiElement<>(personDetailContainer, personDetailController)
        );
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
            libraryViewManager.showPhotoGrid();
            photoGridManager.clear();
            return;
        }
        if (selectedItem.getValue()
                        .type() == NodeType.DUPLICATES_FILE_ROOT) {
            clearSearchState();
            libraryViewManager.showDuplicatesView();
            return;
        }
        if (selectedItem.getValue()
                        .type() == NodeType.DUPLICATES_FOLDER_ROOT) {
            clearSearchState();
            libraryViewManager.showFolderDuplicatesView();
            return;
        }
        NodePayload payload = selectedItem.getValue()
                                          .payload();
        if (payload == null) {
            libraryViewManager.showPhotoGrid();
            photoGridManager.clear();
            return;
        }
        clearSearchState();
        switch (payload) {
            case FolderPayload fp -> {
                libraryViewManager.showPhotoGrid();
                photoGridManager.loadPhotosForFolder(fp.folderId());
            }
            case TimelinePayload tp when tp.month() != null -> {
                libraryViewManager.showPhotoGrid();
                photoGridManager.loadPhotosForTimeline(tp.year(), tp.month(), tp.day());
            }
            case TimelinePayload ignored -> {
                libraryViewManager.showPhotoGrid();
                photoGridManager.clear();
            }
            case UndatedPayload ignored -> {
                libraryViewManager.showPhotoGrid();
                photoGridManager.loadPhotosUndated();
            }
            case AlbumPayload ap -> {
                libraryViewManager.showPhotoGrid();
                photoGridManager.loadPhotosForAlbum(ap.albumId());
            }
            case PersonPayload pp ->
                    personDetailController = libraryViewManager.showPersonDetail(pp.personId(), new UiElement<>(personDetailContainer, personDetailController), new PhotoCellResources(this::onShowRightPanel));
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
        libraryViewManager.showPhotoGrid();
        libraryTreeView.getSelectionModel()
                       .clearSelection();
        long myGeneration = photoGridController.initiateChange();
        runOnDaemonThread("Search", () -> {
            try {
                List<Long> photoIds = searchService.search(query, SEARCH_TOP_K);
                List<PhotoRecord> photos = photoIds.stream()
                                                   .map(id -> photoRepository.findById(id)
                                                                             .orElse(null))
                                                   .filter(Objects::nonNull)
                                                   .toList();
                runOnFxThread(() -> {
                    if (myGeneration != photoGridController.currentChange()) {
                        return;
                    }
                    photoGridManager.populate(photos);
                });
            } catch (Exception e) {
                log.error("Search failed for query '{}'", query, e);
                runOnFxThread(() -> {
                    if (myGeneration == photoGridController.currentChange()) {
                        photoGridController.displayError(e.getMessage());
                    }
                });
            }
        });
    }

    @FXML
    public void onClearSearchClicked(ActionEvent event) {
        clearSearchState();
        photoGridManager.clear();
    }

    private void clearSearchState() {
        searchField.clear();
        clearSearchButton.setVisible(false);
    }

    // ── Menu actions ──────────────────────────────────────────────────────────

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

    public void onShowRightPanel(ImageDetails imageDetails) {
        if (rightPanelController == null) {
            LoadedFxml<RightPanelController> rightPanelLoaded = fxmlLoader.load(FxmlView.RIGHT_PANEL, null);
            rightPanelController = rightPanelLoaded.controller();
            rootBorderPane.setRight(rightPanelLoaded.parent());
        }
        rightPanelController.showDetails(imageDetails);
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
}
