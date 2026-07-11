package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ThumbnailRecord;
import com.github.curiousoddman.curious_images.domain.dedupe.DuplicateResolutionService;
import com.github.curiousoddman.curious_images.model.DuplicateGroupView;
import com.github.curiousoddman.curious_images.model.LoadedFxml;
import com.github.curiousoddman.curious_images.model.PhotoWithThumbnail;
import com.github.curiousoddman.curious_images.persistence.DuplicateGroupRepository;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.FxmlView;
import com.github.curiousoddman.curious_images.ui.controller.custom.DuplicateCellController;
import com.github.curiousoddman.curious_images.ui.styles.CssClasses;
import com.github.curiousoddman.curious_images.ui.util.AlertHelper;
import com.github.curiousoddman.curious_images.ui.util.StageUtils;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.scene.control.Accordion;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TitledPane;
import javafx.scene.image.Image;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

import static com.github.curiousoddman.curious_images.ui.controller.screen.SlideshowController.getImage;
import static com.github.curiousoddman.curious_images.util.HumanReadableUtils.size;
import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static com.sun.javafx.util.Utils.runOnFxThread;

@Lazy
@Slf4j
@Component
@RequiredArgsConstructor
public class DuplicatesController implements Initializable {

    private final FxmlLoader                 fxmlLoader;
    private final DuplicateGroupRepository   duplicateGroupRepository;
    private final DuplicateResolutionService duplicateResolutionService;

    @FXML
    public Accordion duplicatesAccordion;
    @FXML
    public Button    keepSelectedButton;
    @FXML
    public Button    deleteSelectedButton;

    private Image noImageAvailable;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        duplicatesAccordion.expandedPaneProperty()
                           .addListener((obs, oldPane, newPane) -> updateActionButtonsState());
        keepSelectedButton.setOnMouseEntered(e -> previewAction(true));
        keepSelectedButton.setOnMouseExited(e -> clearPreview());
        deleteSelectedButton.setOnMouseEntered(e -> previewAction(false));
        deleteSelectedButton.setOnMouseExited(e -> clearPreview());
    }

    /**
     * Called by LibraryController whenever the Duplicates tab is selected.
     * The no-image placeholder is shared from the parent to avoid loading it twice.
     */
    public void activate(Image noImageAvailable) {
        this.noImageAvailable = noImageAvailable;
        loadDuplicatesTab();
    }

    // ----------------------------------------------------------------------------------------
    // Loading / populating
    // ----------------------------------------------------------------------------------------

    /**
     * Reloads the Duplicates tab from the DB. Called whenever the tab is selected, and again
     * after a keep/delete action completes so the view reflects what's left.
     */
    private void loadDuplicatesTab() {
        runOnDaemonThread("LoadDuplicatesTab", () -> {
            List<DuplicateGroupView> groups = duplicateGroupRepository.findAllGroupsWithMembers();
            runOnFxThread(() -> populateDuplicatesAccordion(groups));
        });
    }

    private void populateDuplicatesAccordion(List<DuplicateGroupView> groups) {
        ObservableList<TitledPane> panes = duplicatesAccordion.getPanes();
        panes.setAll(groups.stream()
                           .map(this::buildDuplicateGroupPane)
                           .toList());
        if (!panes.isEmpty()) {
            duplicatesAccordion.setExpandedPane(panes.getFirst());
        }
        updateActionButtonsState();
    }

    private TitledPane buildDuplicateGroupPane(DuplicateGroupView group) {
        List<DuplicateCell> cells     = new ArrayList<>();
        FlowPane            cellsPane = new FlowPane(10.0, 10.0);
        cellsPane.setPadding(new Insets(10.0));
        for (PhotoWithThumbnail pwt : group.photos()) {
            DuplicateCell cell = createDuplicateCell(cells, pwt.photo(), pwt.thumbnail());
            cells.add(cell);
            VBox container = cell.container();
            cellsPane.getChildren()
                     .add(container);
        }

        // Wire slideshow click for each cell in this duplicate group
        List<PhotoRecord> groupPhotos = cells.stream()
                                             .map(DuplicateCell::photo)
                                             .toList();
        for (int i = 0; i < cells.size(); i++) {
            final int idx = i;
            cells.get(i)
                 .container()
                 .setOnMouseClicked(e -> {
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
        return "%s · %d photos".formatted(ext, group.photos()
                                                    .size());
    }

    /**
     * One photo within a duplicate group: thumbnail, full metadata shown inline (not a hover
     * tooltip — the whole point is comparing photos side by side), and a "keep" checkbox.
     */
    private DuplicateCell createDuplicateCell(List<DuplicateCell> cells, PhotoRecord photo, ThumbnailRecord thumbnail) {
        LoadedFxml<DuplicateCellController> loaded = fxmlLoader.load(FxmlView.DUPLICATE_CELL, null);
        DuplicateCellController             cell   = loaded.controller();

        cell.setThumbnail(getImage(thumbnail, noImageAvailable));
        cell.setInfoText(buildPhotoDetailsText(photo));

        DuplicateCell result = new DuplicateCell(photo, cell.checkBox(), cell.container());
        cell.checkBox()
            .selectedProperty()
            .addListener((obs, was, isNow) -> updateActionButtonsState());
        return result;
    }

    // ----------------------------------------------------------------------------------------
    // Action buttons
    // ----------------------------------------------------------------------------------------

    private PaneData activePaneData() {
        TitledPane expanded = duplicatesAccordion.getExpandedPane();
        return expanded == null ? null : (PaneData) expanded.getUserData();
    }

    /**
     * Both buttons act on the currently expanded pane only, and are disabled with nothing checked.
     */
    private void updateActionButtonsState() {
        PaneData active = activePaneData();
        boolean anyChecked = active != null && active.cells()
                                                     .stream()
                                                     .anyMatch(c -> c.checkBox()
                                                                     .isSelected());
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
            boolean checked = cell.checkBox()
                                  .isSelected();
            boolean willBeKept = keepButtonHovered == checked;
            ObservableList<String> styleClass = cell.container()
                                                    .getStyleClass();
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
            cell.container()
                .getStyleClass()
                .clear();
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
        List<PhotoRecord> toDrop = active.cells()
                                         .stream()
                                         .filter(c -> c.checkBox()
                                                       .isSelected() != keepChecked)
                                         .map(DuplicateCell::photo)
                                         .toList();
        if (toDrop.isEmpty()) {
            return;
        }

        keepSelectedButton.setDisable(true);
        deleteSelectedButton.setDisable(true);
        runOnDaemonThread("", () -> {
            DuplicateResolutionService.Result result = duplicateResolutionService.resolve(active.groupId(), toDrop);
            if (!result.failures()
                       .isEmpty()) {
                showResolutionFailures(result.failures());
            }
            loadDuplicatesTab();
        });
    }

    private void showResolutionFailures(List<DuplicateResolutionService.Failure> failures) {
        StringBuilder sb = new StringBuilder();
        for (DuplicateResolutionService.Failure failure : failures) {
            sb.append("• ")
              .append(failure.photo()
                             .getFilename())
              .append(" — ")
              .append(failure.reason())
              .append('\n');
        }
        runOnFxThread(() -> AlertHelper.showWarning(duplicatesAccordion,
                "Some photos couldn't be moved to the recycle bin and were left in place",
                sb.toString()
                  .strip()));
    }

    // ----------------------------------------------------------------------------------------
    // Slideshow
    // ----------------------------------------------------------------------------------------

    private void openSlideshow(List<PhotoRecord> photos, int startIndex) {
        StageUtils.openSlideshow(photos, startIndex, duplicatesAccordion.getScene(), fxmlLoader);
    }

    // ----------------------------------------------------------------------------------------
    // Helpers shared with photo details text
    // ----------------------------------------------------------------------------------------

    private String buildPhotoDetailsText(PhotoRecord photo) {
        return getPhotoDetailsText(photo, formatFileSize(photo.getFileSize()));
    }

    static String getPhotoDetailsText(PhotoRecord photo, String s) {
        StringBuilder sb = new StringBuilder();
        sb.append(photo.getFilename())
          .append('\n');
        sb.append(photo.getAbsolutePath())
          .append('\n');
        sb.append("Extension:  ")
          .append(photo.getExtension() == null ? "—" : photo.getExtension())
          .append('\n');
        sb.append("Size:       ")
          .append(s)
          .append('\n');
        if (photo.getImageWidth() != null && photo.getImageHeight() != null) {
            sb.append("Dimensions: ")
              .append(photo.getImageWidth())
              .append(" x ")
              .append(photo.getImageHeight())
              .append('\n');
        }
        if (photo.getCaptureDate() != null) {
            sb.append("Captured:    ")
              .append(photo.getCaptureDate());
            if (photo.getCaptureDateSource() != null) {
                sb.append(" (")
                  .append(photo.getCaptureDateSource())
                  .append(')');
            }
            sb.append('\n');
        }
        if (photo.getImportedAt() != null) {
            sb.append("Imported:    ")
              .append(photo.getImportedAt())
              .append('\n');
        }
        if (photo.getLastSeenAt() != null) {
            sb.append("Last seen:    ")
              .append(photo.getLastSeenAt());
        }
        return sb.toString()
                 .strip();
    }

    private static String formatFileSize(Long bytes) {
        return size(bytes);
    }

    // ----------------------------------------------------------------------------------------
    // Inner records
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
}
