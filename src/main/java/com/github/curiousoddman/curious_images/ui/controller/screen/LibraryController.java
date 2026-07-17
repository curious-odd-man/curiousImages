package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.domain.ai.ModelDownloadJob;
import com.github.curiousoddman.curious_images.domain.ai.ModelPaths;
import com.github.curiousoddman.curious_images.domain.search.SearchService;
import com.github.curiousoddman.curious_images.domain.user.prefs.UserPreferencesService;
import com.github.curiousoddman.curious_images.event.model.BackgroundProcessEvent;
import com.github.curiousoddman.curious_images.event.payload.BackgroundProcessPayload;
import com.github.curiousoddman.curious_images.event.types.BackgroundProcessEventType;
import com.github.curiousoddman.curious_images.model.LoadedFxml;
import com.github.curiousoddman.curious_images.model.bundle.AddFilesBundle;
import com.github.curiousoddman.curious_images.model.bundle.RescanBundle;
import com.github.curiousoddman.curious_images.persistence.AlbumPhotoRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoRepository;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.FxmlView;
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
import com.github.curiousoddman.curious_images.ui.nodes.photogrid.PhotoGridRow;
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
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Slider;
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

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

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
    private final AlbumPhotoRepository   albumPhotoRepository;
    private final SearchService          searchService;
    private final JobManager             jobManager;
    private final ModelPaths             modelPaths;
    private final TreeManager            treeManager;
    private final PhotoGridManager       photoGridManager;

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

    /**
     * Guards against a stale background page-load or full-selection-load callback overwriting a
     * newer selection: every time the user switches selection (folder/timeline/album/search/
     * undated) this is incremented, and any in-flight callback whose captured value no longer
     * matches is discarded. Coarser than per-cell tracking — see implementation plan §4 ("only
     * folder/selection-switch staleness matters, not per-cell reassignment").
     */
    private final AtomicLong selectionGeneration = new AtomicLong();


    // ── Runtime state ─────────────────────────────────────────────────────────

    private DuplicatesController       duplicatesController;
    private FolderDuplicatesController folderDuplicatesController;

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

        photoGridManager.initialize(photoCountLabel, photoGridListView, thumbnailSizeSlider, selectionGeneration);
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
            photoGridManager.clear();
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
            photoGridManager.clear();
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
                photoGridManager.clear();
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
                photoGridManager.populate(photos);
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
                    photoGridManager.populate(photos);
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
        photoGridManager.clear();
    }

    private void clearSearchState() {
        searchField.clear();
        clearSearchButton.setVisible(false);
        photoCountLabel.setText("");
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
