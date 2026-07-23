package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaPhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ThumbnailRecord;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.ThumbnailUtils;
import com.github.curiousoddman.curious_images.domain.dedupe.DuplicateResolutionService;
import com.github.curiousoddman.curious_images.event.model.ThumbnailsReadyEvent;
import com.github.curiousoddman.curious_images.model.DupResolveStrategy;
import com.github.curiousoddman.curious_images.model.DuplicateGroup;
import com.github.curiousoddman.curious_images.model.GridCellData;
import com.github.curiousoddman.curious_images.model.LoadedFxml;
import com.github.curiousoddman.curious_images.model.Media;
import com.github.curiousoddman.curious_images.model.MediaWithThumbnail;
import com.github.curiousoddman.curious_images.model.PhotoFailure;
import com.github.curiousoddman.curious_images.model.bundle.DuplicateCellDataBundle;
import com.github.curiousoddman.curious_images.persistence.DuplicateGroupRepository;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.FxmlView;
import com.github.curiousoddman.curious_images.ui.controller.custom.DetailRow;
import com.github.curiousoddman.curious_images.ui.controller.custom.DuplicateCellController;
import com.github.curiousoddman.curious_images.ui.styles.CssClasses;
import com.github.curiousoddman.curious_images.util.async.DelayedAction;
import com.github.curiousoddman.curious_images.util.async.jobs.JobManager;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
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
import java.util.stream.Collectors;

import static com.github.curiousoddman.curious_images.model.DupResolveStrategy.isDropped;
import static com.github.curiousoddman.curious_images.ui.controller.screen.SlideshowController.getImage;
import static com.github.curiousoddman.curious_images.ui.util.UiUtils.setupDuplicateButtonHover;
import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static com.sun.javafx.util.Utils.runOnFxThread;

@Lazy
@Slf4j
@Component
@RequiredArgsConstructor
public class DuplicatesController implements Initializable {

    private static final int THUMBNAIL_GEN_DEBOUNCE_MS = 150;

    private final FxmlLoader                 fxmlLoader;
    private final DuplicateGroupRepository   duplicateGroupRepository;
    private final DuplicateResolutionService duplicateResolutionService;
    private final ThumbnailRepository        thumbnailRepository;
    private final JobManager                 jobManager;

    @FXML
    public VBox   dupliacteItemsVbox;
    @FXML
    public Button keepSelectedButton;
    @FXML
    public Button deleteSelectedButton;
    @FXML
    public Button previousDuplicateButton;
    @FXML
    public Label  duplicateCounterLabel;
    @FXML
    public Button nextDuplicateButton;
    @FXML
    public Button deleteAllButton;
    @FXML
    public Button keepAllButton;

    private final DelayedAction                      thumbnailGenDebounce   = new DelayedAction(THUMBNAIL_GEN_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    private final Set<Long>                          pendingThumbnailGenIds = new HashSet<>();
    private final Map<Long, DuplicateCellController> visiblePhotoCells      = new HashMap<>();

    private final List<DuplicateGroup> knownGroups = new ArrayList<>();


    private int currentGroupIndex = 0;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        setupDuplicateButtonHover(
                keepAllButton,
                deleteAllButton,
                keepSelectedButton,
                deleteSelectedButton,
                this::previewAction,
                this::clearPreview
        );
    }

    @FXML
    public void showPreviousDuplicate(ActionEvent event) {
        log.info("prev clicked {}", currentGroupIndex);
        if (currentGroupIndex > 0) {
            currentGroupIndex--;
            loadCurrentDuplicatesView();
        }
    }

    @FXML
    public void showNextDuplicate(ActionEvent event) {
        log.info("next clicked {}", currentGroupIndex);
        if (currentGroupIndex < knownGroups.size() - 1) {
            currentGroupIndex++;
            loadCurrentDuplicatesView();
        }
    }

    @FXML
    public void onKeepSelectedClicked(ActionEvent event) {
        resolveActivePane(DupResolveStrategy.KEEP_CHECKED);
        showNextDuplicate(event);
    }

    @FXML
    public void onDeleteSelectedClicked(ActionEvent event) {
        resolveActivePane(DupResolveStrategy.REMOVE_CHECKED);
        showNextDuplicate(event);
    }

    @FXML
    public void onKeepAll(ActionEvent event) {
        resolveActivePane(DupResolveStrategy.KEEP_ALL);
        showNextDuplicate(event);
    }

    @FXML
    public void onDeleteAll(ActionEvent event) {
        resolveActivePane(DupResolveStrategy.REMOVE_ALL);
        showNextDuplicate(event);
    }

    public void activateDuplicatesView() {
        knownGroups.clear();
        knownGroups.addAll(duplicateGroupRepository.findAllGroupsWithMembers());
        if (knownGroups.isEmpty()) {
            previousDuplicateButton.setDisable(true);
            nextDuplicateButton.setDisable(true);
            duplicateCounterLabel.setText("Nothing to display. Rerun duplicate detection...");
            return;
        }
        currentGroupIndex = 0;
        loadCurrentDuplicatesView();
    }

    // ----------------------------------------------------------------------------------------
    // Loading / populating
    // ----------------------------------------------------------------------------------------

    private void loadCurrentDuplicatesView() {
        updateDuplicateControls();
        ObservableList<Node> duplicateItems = dupliacteItemsVbox.getChildren();
        visiblePhotoCells.clear();
        keepSelectedButton.setDisable(true);
        deleteSelectedButton.setDisable(true);

        List<Pane> cells = new ArrayList<>();
        runOnDaemonThread("LoadDuplicatesTab", () -> {
            DuplicateGroup currentGroup = knownGroups.get(currentGroupIndex);

            Map<Long, Map<String, DetailRow>> allValues = getPropertiesMarkedDifferent(currentGroup);

            List<Long> ids = new ArrayList<>();

            List<Media> groupPhotos = currentGroup
                    .photos()
                    .stream()
                    .map(MediaWithThumbnail::media)
                    .toList();

            currentGroup
                    .photos()
                    .forEach(pwt -> {
                        Long id = pwt.media()
                                     .getId();
                        DuplicateCell duplicateCell = createDuplicateCell(pwt, allValues, groupPhotos, cells.size());
                        visiblePhotoCells.put(id, duplicateCell.controller());
                        cells.add(duplicateCell.pane());
                        duplicateCell.checkBox()
                                     .selectedProperty()
                                     .addListener((obs, was, isNow) -> updateActionButtonsState());
                        ThumbnailRecord thumbnail = pwt.thumbnail();
                        if (thumbnail == null || !ThumbnailUtils.hasCachedFile(thumbnail)) {
                            ids.add(id);
                        }
                    });

            runOnFxThread(() -> duplicateItems.setAll(cells));

            if (!ids.isEmpty()) {
                queueThumbnailGeneration(ids);
            }
        });
    }

    private static Map<Long, Map<String, DetailRow>> getPropertiesMarkedDifferent(DuplicateGroup currentGroup) {
        Map<Long, Map<String, DetailRow>> allValues = currentGroup
                .photos()
                .stream()
                .map(MediaWithThumbnail::media)
                .collect(Collectors.toMap(
                        Media::getId,
                        GridCellData::getMediaDetails
                ));

        Set<String> allKeys = allValues
                .values()
                .stream()
                .map(Map::keySet)
                .flatMap(Set::stream)
                .collect(Collectors.toSet());


        for (String key : allKeys) {
            List<DetailRow> detailRowStream = allValues.values()
                                                       .stream()
                                                       .map(m -> m.get(key))
                                                       .toList();
            Set<String> list = detailRowStream
                    .stream()
                    .map(DetailRow::getValue)
                    .collect(Collectors.toSet());

            if (list.size() != 1) {
                detailRowStream.forEach(dr -> dr.setDifferent(true));
            }
        }
        return allValues;
    }

    private DuplicateCell createDuplicateCell(MediaWithThumbnail pwt, Map<Long, Map<String, DetailRow>> allValues, List<Media> groupPhotos, int index) {
        Map<String, DetailRow> details = allValues.get(pwt.media()
                                                          .getId());
        DuplicateCellDataBundle resourceBundle = new DuplicateCellDataBundle(
                getImage(pwt.thumbnail(), null),
                details.values(),
                groupPhotos,
                index
        );
        LoadedFxml<DuplicateCellController> loaded = fxmlLoader.load(FxmlView.DUPLICATE_CELL, resourceBundle);
        DuplicateCellController             cell   = loaded.controller();

        return new DuplicateCell(pwt, cell.checkBox(), cell, (Pane) loaded.parent());
    }

    // ----------------------------------------------------------------------------------------
    // Thumbnail generation
    // ----------------------------------------------------------------------------------------

    /**
     * Debounced request for real-thumbnail generation, mirroring {@code LibraryController}'s
     * approach: repeated calls while the tab is loading/repopulating coalesce into one job
     * submission instead of firing a job per group.
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
     * Swaps the placeholder image of any still-visible duplicate cell for the real thumbnail,
     * once {@code ThumbnailGenerationJob} has generated it. Photo IDs not currently in
     * {@link #visiblePhotoCells} (accordion reloaded/collapsed since the request was made) are
     * simply skipped — not an error, since {@link #loadCurrentDuplicatesView} always does a fresh, correct
     * lookup on the next reload.
     * <p>
     * Written directly against {@link DuplicateCellController} rather than reusing
     * {@code ThumbnailUtils.updateThumbnailImage}, since that helper is typed to
     * {@code PhotoCellController} and this tab's cell controller doesn't share that interface.
     */
    @EventListener
    public void onThumbnailsReady(ThumbnailsReadyEvent event) {
        List<Long> relevantIds = event.getPhotoIds()
                                      .stream()
                                      .filter(visiblePhotoCells::containsKey)
                                      .toList();
        if (relevantIds.isEmpty()) {
            return;
        }
        runOnDaemonThread("DuplicatesThumbnailUpdate", () -> {
            Map<Long, ThumbnailRecord> thumbs = thumbnailRepository.findByPhotoIds(relevantIds);
            for (Map.Entry<Long, ThumbnailRecord> entry : thumbs.entrySet()) {
                ThumbnailRecord thumbnail = entry.getValue();
                if (!ThumbnailUtils.hasCachedFile(thumbnail)) {
                    continue;
                }
                DuplicateCellController cell = visiblePhotoCells.get(entry.getKey());
                if (cell != null) {
                    runOnFxThread(() -> cell.setThumbnail(ThumbnailUtils.loadThumbnailImage(thumbnail)));
                }
            }
        });
    }

    // ----------------------------------------------------------------------------------------
    // Action buttons
    // ----------------------------------------------------------------------------------------

    private void previewAction(DupResolveStrategy strategy) {
        visiblePhotoCells.forEach((key, controller) -> {
            boolean checked = controller.checkBox()
                                        .isSelected();
            boolean willBeKept = !isDropped(strategy, checked);
            ObservableList<String> styleClass = controller.container()
                                                          .getStyleClass();
            styleClass.add(willBeKept ? CssClasses.KEEP_PREVIEW : CssClasses.DROP_PREVIEW);
            styleClass.remove(willBeKept ? CssClasses.DROP_PREVIEW : CssClasses.KEEP_PREVIEW);
        });
    }

    private void clearPreview() {
        visiblePhotoCells.forEach((key, controller) ->
                controller.container()
                          .getStyleClass()
                          .clear()
        );
    }

    private void resolveActivePane(DupResolveStrategy strategy) {
        List<MediaPhotoRecord> toDrop       = new ArrayList<>();
        DuplicateGroup         currentGroup = knownGroups.get(currentGroupIndex);
        for (MediaWithThumbnail mediaWithThumbnail : currentGroup.photos()) {
            MediaPhotoRecord MediaPhotoRecord = mediaWithThumbnail.media()
                                                                  .photo();
            DuplicateCellController duplicateCellController = visiblePhotoCells.get(MediaPhotoRecord.getId());
            boolean currentImageSelected = duplicateCellController.checkBox()
                                                                  .isSelected();

            if (isDropped(strategy, currentImageSelected)) {
                toDrop.add(MediaPhotoRecord);
            }
        }

        log.info("Ready to drop {} rows", toDrop.size());
        if (toDrop.isEmpty() && strategy != DupResolveStrategy.KEEP_ALL) {
            return;
        }

        keepSelectedButton.setDisable(true);
        deleteSelectedButton.setDisable(true);
        runOnDaemonThread("", () -> {
            DuplicateResolutionService.Result result = duplicateResolutionService.resolve(currentGroup.groupId(), toDrop, strategy);
            if (!result.failures()
                       .isEmpty()) {
                PhotoFailure.displayAlert(result.failures(), dupliacteItemsVbox);
            }
            loadCurrentDuplicatesView();
        });
    }

// ----------------------------------------------------------------------------------------
// Helpers shared with photoWithThumbnail details value
// ----------------------------------------------------------------------------------------

    private void updateActionButtonsState() {
        boolean anyChecked = visiblePhotoCells.values()
                                              .stream()
                                              .anyMatch(c -> c.checkBox()
                                                              .isSelected());
        keepSelectedButton.setDisable(!anyChecked);
        deleteSelectedButton.setDisable(!anyChecked);
    }

    private void updateDuplicateControls() {
        previousDuplicateButton.setDisable(currentGroupIndex == 0);
        nextDuplicateButton.setDisable(currentGroupIndex == knownGroups.size() - 1);
        duplicateCounterLabel.setText("Duplicate " + (currentGroupIndex + 1) + " of " + knownGroups.size());
    }

    private record DuplicateCell(MediaWithThumbnail mediaWithThumbnail, CheckBox checkBox,
                                 DuplicateCellController controller, Pane pane) {
    }
}