package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaPhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ThumbnailRecord;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.ThumbnailUtils;
import com.github.curiousoddman.curious_images.domain.dedupe.DuplicateResolutionService;
import com.github.curiousoddman.curious_images.domain.dedupe.FolderDuplicateGroupingService;
import com.github.curiousoddman.curious_images.event.model.ThumbnailsReadyEvent;
import com.github.curiousoddman.curious_images.model.DupResolveStrategy;
import com.github.curiousoddman.curious_images.model.FolderDuplicatePair;
import com.github.curiousoddman.curious_images.model.LoadedFxml;
import com.github.curiousoddman.curious_images.model.Media;
import com.github.curiousoddman.curious_images.model.MediaWithThumbnail;
import com.github.curiousoddman.curious_images.model.PhotoFailure;
import com.github.curiousoddman.curious_images.model.bundle.FolderDuplicateCellBundle;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.FxmlView;
import com.github.curiousoddman.curious_images.ui.controller.custom.FolderDuplicateCellController;
import com.github.curiousoddman.curious_images.ui.styles.CssClasses;
import com.github.curiousoddman.curious_images.ui.util.UiUtils;
import com.github.curiousoddman.curious_images.util.async.DelayedAction;
import com.github.curiousoddman.curious_images.util.async.jobs.JobManager;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.github.curiousoddman.curious_images.model.DupResolveStrategy.isDropped;
import static com.github.curiousoddman.curious_images.ui.controller.screen.SlideshowController.getImage;
import static com.github.curiousoddman.curious_images.ui.util.UiUtils.setupDuplicateButtonHover;
import static com.github.curiousoddman.curious_images.util.HumanReadableUtils.size;
import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static com.sun.javafx.util.Utils.runOnFxThread;

/**
 * Folder-level Duplicates tab: a list of folder pairs that share "clean" 2-folder duplicate
 * groups (see {@link FolderDuplicateGroupingService}), and a detail panel to resolve one pair at
 * a time — "keep everything in folder A, drop the duplicates in folder B" (or vice versa),
 * applied across every qualifying group in one action.
 * <p>
 * Mirrors {@link DuplicatesController}'s thumbnail-loading/debounce pattern and reuses its
 * {@link DupResolveStrategy} enum and keep/drop branch logic verbatim (see
 * file-level buttons of the same name.
 */
@Lazy
@Slf4j
@Component
@RequiredArgsConstructor
public class FolderDuplicatesController implements Initializable {

    private static final int THUMBNAIL_GEN_DEBOUNCE_MS = 150;
    private static final int TILE_SIZE                 = 120;

    private final FxmlLoader                     fxmlLoader;
    private final FolderDuplicateGroupingService folderDuplicateGroupingService;
    private final DuplicateResolutionService     duplicateResolutionService;
    private final ThumbnailRepository            thumbnailRepository;
    private final JobManager                     jobManager;

    @FXML
    public VBox                          listPane;
    @FXML
    public Label                         listEmptyLabel;
    @FXML
    public ListView<FolderDuplicatePair> pairListView;

    @FXML
    public VBox       detailPane;
    @FXML
    public Button     backButton;
    @FXML
    public Label      pairTitleLabel;
    @FXML
    public AnchorPane folderACellContainer;
    @FXML
    public AnchorPane folderBCellContainer;
    @FXML
    public Button     keepAllButton;
    @FXML
    public Button     deleteAllButton;
    @FXML
    public Button     keepSelectedButton;
    @FXML
    public Button     deleteSelectedButton;

    private final DelayedAction             thumbnailGenDebounce   = new DelayedAction(THUMBNAIL_GEN_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    private final Set<Long>                 pendingThumbnailGenIds = new HashSet<>();
    private final Map<Long, ImageView>      visiblePhotoTiles      = new HashMap<>();
    private final List<FolderDuplicatePair> knownPairs             = new ArrayList<>();

    private FolderDuplicatePair           activePair;
    private FolderDuplicateCellController folderACell;
    private FolderDuplicateCellController folderBCell;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        pairListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(FolderDuplicatePair pair, boolean empty) {
                super.updateItem(pair, empty);
                if (empty || pair == null) {
                    setText(null);
                    setOnMouseClicked(null);
                } else {
                    setText(pair.folderAPath() + "   ⇄   " + pair.folderBPath()
                            + "    (" + pair.groupCount() + " duplicate group" + (pair.groupCount() == 1 ? "" : "s") + ")");
                    setOnMouseClicked(e -> showDetail(pair));
                }
            }
        });

        setupDuplicateButtonHover(
                keepAllButton,
                deleteAllButton,
                keepSelectedButton,
                deleteSelectedButton,
                this::previewAction,
                this::clearPreview
        );
    }

    /**
     * Called by {@code LibraryController} when the "Duplicates → Folders" tree node is selected —
     * mirrors {@code DuplicatesController#activateDuplicatesView}.
     */
    public void activateFolderDuplicatesView() {
        showList();
    }

    // ----------------------------------------------------------------------------------------
    // List <-> detail
    // ----------------------------------------------------------------------------------------

    private void showList() {
        activePair = null;
        folderACell = null;
        folderBCell = null;
        detailPane.setVisible(false);
        detailPane.setManaged(false);
        listPane.setVisible(true);
        listPane.setManaged(true);

        knownPairs.clear();
        knownPairs.addAll(folderDuplicateGroupingService.buildFolderPairs());
        listEmptyLabel.setVisible(knownPairs.isEmpty());
        listEmptyLabel.setManaged(knownPairs.isEmpty());
        pairListView.getItems()
                    .setAll(knownPairs);
    }

    @FXML
    public void onBackClicked(ActionEvent event) {
        showList();
    }

    private void showDetail(FolderDuplicatePair pair) {
        activePair = pair;
        listPane.setVisible(false);
        listPane.setManaged(false);
        detailPane.setVisible(true);
        detailPane.setManaged(true);
        pairTitleLabel.setText(pair.folderAPath() + "   ⇄   " + pair.folderBPath());
        visiblePhotoTiles.clear();
        keepSelectedButton.setDisable(true);
        deleteSelectedButton.setDisable(true);

        // Cell construction (fxmlLoader.load) and tile population happen off the FX thread before
        // anything is attached to the live scene graph, then swapped in on the FX thread — the
        // same convention DuplicatesController#loadCurrentDuplicatesView uses for its per-media
        // cells.
        runOnDaemonThread("LoadFolderDuplicatePair", () -> {
            FolderDuplicateCellController aCell = loadFolderCell(pair, pair.folderAId(), pair.folderAPath());
            FolderDuplicateCellController bCell = loadFolderCell(pair, pair.folderBId(), pair.folderBPath());
            populateSide(aCell, pair.folderAId());
            populateSide(bCell, pair.folderBId());

            runOnFxThread(() -> {
                folderACell = aCell;
                folderBCell = bCell;
                attach(folderACellContainer, aCell.container());
                attach(folderBCellContainer, bCell.container());
                aCell.checkBox()
                     .selectedProperty()
                     .addListener((o, was, isNow) -> updateActionButtonsState());
                bCell.checkBox()
                     .selectedProperty()
                     .addListener((o, was, isNow) -> updateActionButtonsState());
            });
        });
    }

    private FolderDuplicateCellController loadFolderCell(FolderDuplicatePair pair, long folderId, String path) {
        FolderDuplicateCellBundle bundle = new FolderDuplicateCellBundle(
                path, pair.groupCount(), pair.photoCount(folderId), pair.totalSize(folderId), false);
        LoadedFxml<FolderDuplicateCellController> loaded     = fxmlLoader.load(FxmlView.FOLDER_DUPLICATE_CELL, bundle);
        FolderDuplicateCellController             controller = loaded.controller();
        controller.setFolderId(folderId);
        return controller;
    }

    private static void attach(AnchorPane container, Node view) {
        AnchorPane.setTopAnchor(view, 0.0);
        AnchorPane.setBottomAnchor(view, 0.0);
        AnchorPane.setLeftAnchor(view, 0.0);
        AnchorPane.setRightAnchor(view, 0.0);
        container.getChildren()
                 .setAll(view);
    }

    // ----------------------------------------------------------------------------------------
    // Thumbnails
    // ----------------------------------------------------------------------------------------

    private void populateSide(FolderDuplicateCellController cellController, long folderId) {
        FlowPane pane = cellController.thumbnailFlowPane();
        pane.getChildren()
            .clear();

        List<MediaWithThumbnail> sidePhotos = activePair.groups()
                                                        .stream()
                                                        .flatMap(g -> g.photos()
                                                                       .stream())
                                                        .filter(pwt -> folderId == pwt.media()
                                                                                      .getFolderId())
                                                        .toList();

        List<Long> needsThumbnail = new ArrayList<>();
        for (int i = 0; i < sidePhotos.size(); i++) {
            MediaWithThumbnail pwt = sidePhotos.get(i);
            List<Media> orderedForSlideshow = sidePhotos.stream()
                                                        .map(MediaWithThumbnail::media)
                                                        .toList();
            pane.getChildren()
                .add(createPhotoTile(pwt, orderedForSlideshow, i));
            ThumbnailRecord thumbnail = pwt.thumbnail();
            if (thumbnail == null || !ThumbnailUtils.hasCachedFile(thumbnail)) {
                needsThumbnail.add(pwt.media()
                                      .getId());
            }
        }
        if (!needsThumbnail.isEmpty()) {
            queueThumbnailGeneration(needsThumbnail);
        }
    }

    private Node createPhotoTile(MediaWithThumbnail pwt, List<Media> orderedFolderPhotos, int index) {
        MediaPhotoRecord photo     = pwt.media()
                                        .photo();
        ImageView        imageView = new ImageView(getImage(pwt.thumbnail(), null));
        imageView.setFitWidth(TILE_SIZE);
        imageView.setFitHeight(TILE_SIZE);
        imageView.setPreserveRatio(true);
        Tooltip.install(imageView, new Tooltip(photo.getFilename() + "\n" + size(photo.getFileSize())));
        imageView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 1 && e.getButton() == MouseButton.PRIMARY) {
                UiUtils.openSlideshow(orderedFolderPhotos, index, imageView.getScene(), fxmlLoader);
            }
        });
        visiblePhotoTiles.put(photo.getId(), imageView);
        return imageView;
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

    @EventListener
    public void onThumbnailsReady(ThumbnailsReadyEvent event) {
        List<Long> relevantIds = event.getPhotoIds()
                                      .stream()
                                      .filter(visiblePhotoTiles::containsKey)
                                      .toList();
        if (relevantIds.isEmpty()) {
            return;
        }
        runOnDaemonThread("FolderDuplicatesThumbnailUpdate", () -> {
            Map<Long, ThumbnailRecord> thumbs = thumbnailRepository.findByPhotoIds(relevantIds);
            for (Map.Entry<Long, ThumbnailRecord> entry : thumbs.entrySet()) {
                ThumbnailRecord thumbnail = entry.getValue();
                if (!ThumbnailUtils.hasCachedFile(thumbnail)) {
                    continue;
                }
                ImageView tile = visiblePhotoTiles.get(entry.getKey());
                if (tile != null) {
                    runOnFxThread(() -> tile.setImage(ThumbnailUtils.loadThumbnailImage(thumbnail)));
                }
            }
        });
    }

    // ----------------------------------------------------------------------------------------
    // Action buttons
    // ----------------------------------------------------------------------------------------


    private void previewAction(DupResolveStrategy strategy) {
        applyPreview(folderACell, strategy);
        applyPreview(folderBCell, strategy);
    }

    private void applyPreview(FolderDuplicateCellController cell, DupResolveStrategy strategy) {
        if (cell == null) {
            return;
        }
        boolean checked = cell.checkBox()
                              .isSelected();
        boolean willBeKept = !isDropped(strategy, checked);
        var styleClass = cell.container()
                             .getStyleClass();
        styleClass.add(willBeKept ? CssClasses.KEEP_PREVIEW : CssClasses.DROP_PREVIEW);
        styleClass.remove(willBeKept ? CssClasses.DROP_PREVIEW : CssClasses.KEEP_PREVIEW);
    }

    private void clearPreview() {
        if (folderACell != null) {
            folderACell.container()
                       .getStyleClass()
                       .clear();
        }
        if (folderBCell != null) {
            folderBCell.container()
                       .getStyleClass()
                       .clear();
        }
    }

    @FXML
    public void onKeepAll(ActionEvent event) {
        resolveActivePair(DupResolveStrategy.KEEP_ALL);
    }

    @FXML
    public void onDeleteAll(ActionEvent event) {
        resolveActivePair(DupResolveStrategy.REMOVE_ALL);
    }

    @FXML
    public void onKeepSelectedClicked(ActionEvent event) {
        resolveActivePair(DupResolveStrategy.KEEP_CHECKED);
    }

    @FXML
    public void onDeleteSelectedClicked(ActionEvent event) {
        resolveActivePair(DupResolveStrategy.REMOVE_CHECKED);
    }

    private void updateActionButtonsState() {
        boolean anyChecked = (folderACell != null && folderACell.checkBox()
                                                                .isSelected())
                || (folderBCell != null && folderBCell.checkBox()
                                                      .isSelected());
        keepSelectedButton.setDisable(!anyChecked);
        deleteSelectedButton.setDisable(!anyChecked);
    }

    private void resolveActivePair(DupResolveStrategy strategy) {
        if (activePair == null || folderACell == null || folderBCell == null) {
            return;
        }
        boolean aDropped = isDropped(strategy, folderACell.checkBox()
                                                          .isSelected());
        boolean bDropped = isDropped(strategy, folderBCell.checkBox()
                                                          .isSelected());

        // Nothing would actually be dropped — mirrors DuplicatesController's "empty toDrop, non
        // KEEP_ALL strategy" no-op guard, so an accidental click with nothing marked doesn't churn.
        if (strategy != DupResolveStrategy.KEEP_ALL && !aDropped && !bDropped) {
            return;
        }

        Set<Long> keptFolderIds = new HashSet<>();
        if (!aDropped) {
            keptFolderIds.add(activePair.folderAId());
        }
        if (!bDropped) {
            keptFolderIds.add(activePair.folderBId());
        }

        FolderDuplicatePair pairToResolve = activePair;
        keepSelectedButton.setDisable(true);
        deleteSelectedButton.setDisable(true);
        runOnDaemonThread("ResolveFolderPair", () -> {
            DuplicateResolutionService.FolderPairResult result =
                    duplicateResolutionService.resolveFolderPair(pairToResolve, strategy, keptFolderIds);
            if (!result.failures()
                       .isEmpty()) {
                PhotoFailure.displayAlert(result.failures(), detailPane);
            }
            runOnFxThread(this::showList);
        });
    }
}
