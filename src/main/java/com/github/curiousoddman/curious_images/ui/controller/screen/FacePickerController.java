package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.dbobj.tables.records.ClusterRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PersonRecord;
import com.github.curiousoddman.curious_images.domain.ai.PersonCorrectionService;
import com.github.curiousoddman.curious_images.event.model.TreeViewUpdateEvent;
import com.github.curiousoddman.curious_images.event.payload.TreeViewUpdatePayload;
import com.github.curiousoddman.curious_images.model.LoadedFxml;
import com.github.curiousoddman.curious_images.persistence.ClusterRepository;
import com.github.curiousoddman.curious_images.persistence.FaceRepository;
import com.github.curiousoddman.curious_images.persistence.PersonRepository;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.FxmlView;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Dialog;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * Controller for {@code face_picker.fxml} — a modal view of every face tagged for a person,
 * grouped into one collapsible section per prototype (cluster) the person owns, plus an
 * "Unclustered" section for anything not currently assigned to one — so it's visually obvious
 * which faces the app currently considers "the same appearance group" for this person.
 * Originally just a cover-photo chooser (plain click → pick cover → close, still unchanged —
 * see {@link #onFaceChosen}), this dialog is also the FR1/FR2/FR3/FR5 correction entry point
 * from {@code face-person-correction-requirements.md}:
 * <ul>
 *   <li>Ctrl/Shift-click a face to add it to a multi-selection (FR3), without closing the
 *       dialog or touching the cover face.</li>
 *   <li>Right-click a face for "Not this person…" (FR1), "Confirm" (FR2), or "Exclude" (FR5).
 *       If the clicked face is part of an active multi-selection, the action applies to the
 *       whole selection; otherwise it applies to just that one face. "Not this person…" opens
 *       {@link #promptDestinationDialog} — a thumbnail-based picker (see below), not a menu.</li>
 *   <li>"Move Selected to…" in the toolbar opens the same dialog, explicitly scoped to the
 *       current multi-selection (FR3's "Move selected to…" toolbar action).</li>
 * </ul>
 * <b>Destination picker.</b> Choosing a destination is a visual-recognition task ("which of
 * this person's prototypes does this face actually belong to?"), so it's a {@link Dialog} with a
 * thumbnail next to every option rather than a text-only menu: one row per existing prototype of
 * each eligible person (showing a representative face from that prototype and its member count,
 * with {@link PersonCorrectionService#suggestDestinationCluster}'s pick marked — never
 * auto-applied), a "New prototype for …" row per person, and a "New person…" row.
 * <p>
 * Reassigning or excluding a face removes it from this grid immediately — since this dialog only
 * ever shows one person's faces, a face that just left that person no longer belongs here; if
 * that empties a whole prototype's section, the section itself is removed. If a move/exclude
 * leaves some other person owning zero prototypes, this controller also prompts to delete that
 * now-empty person (see {@link #handleOrphanedPersons}) — the correction service itself never
 * deletes a person on its own initiative.
 * <p>
 * Usage (cover-pick path unchanged):
 * <pre>
 *     LoadedFxml&lt;FacePickerController&gt; loaded = fxmlLoader.load(FxmlView.FACE_PICKER, null);
 *     FacePickerController controller = loaded.controller();
 *     controller.init(allFaces, currentPerson.getCoverFaceId(), currentPerson.getId());
 *
 *     Stage stage = new Stage();
 *     stage.initModality(Modality.APPLICATION_MODAL);
 *     stage.initOwner(ownerWindow);
 *     stage.setScene(new Scene(loaded.parent()));
 *     controller.setStage(stage);
 *     stage.showAndWait();
 *
 *     FaceRecord chosen = controller.getSelectedFace(); // null if cancelled
 *     boolean    changed = controller.didCorrectionsHappen(); // true if any FR1/3/5 action fired —
 *                                                              // caller should reload the person's
 *                                                              // face list either way.
 * </pre>
 */
@Lazy
@Slf4j
@Component
@RequiredArgsConstructor
public class FacePickerController implements Initializable {

    private static final long UNCLUSTERED_KEY = -1L;

    private final FxmlLoader                fxmlLoader;
    private final PersonRepository          personRepo;
    private final ClusterRepository         clusterRepo;
    private final FaceRepository            faceRepo;
    private final PersonCorrectionService   personCorrectionService;
    private final ApplicationEventPublisher eventPublisher;

    @FXML
    public VBox   faceSectionsBox;
    @FXML
    public Button moveSelectedMenuButton;
    @FXML
    public Label  selectionCountLabel;

    /**
     * -- SETTER --
     * Must be called by the owner (
     * etc. happens outside this
     * controller) so
     * and a face click can close the dialog.
     */
    @Setter
    private Stage      stage;
    /**
     * -- GETTER --
     * The face the user clicked, or
     * if they cancelled/closed the dialog without
     * choosing one. Only meaningful after
     * has returned.
     */
    @Getter
    private FaceRecord selectedFace;
    private long       currentPersonId;
    private boolean    correctionsHappened;

    private final Map<Long, FacePickerCellController> cellsById          = new LinkedHashMap<>();
    private final Map<Long, Long>                     clusterKeyByFaceId = new LinkedHashMap<>();
    private final Map<Long, TitledPane>               sectionsByKey      = new LinkedHashMap<>();
    private final Set<Long>                           selectedIds        = new LinkedHashSet<>();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // Grid contents depend on which person is being edited, so they're populated in init()
        // rather than here.
        if (moveSelectedMenuButton != null) {
            moveSelectedMenuButton.setDisable(true);
        }
        updateSelectionLabel();
    }

    /**
     * True if any FR1/FR3/FR5 correction fired during this dialog session — the caller should
     * reload the person's face list from the DB even if the dialog was otherwise "cancelled",
     * since faces may have moved out from under it.
     */
    public boolean didCorrectionsHappen() {
        return correctionsHappened;
    }

    /**
     * Populates the view with one collapsible section per prototype (cluster) this person owns
     * — plus a trailing "Unclustered" section for any face not currently assigned to one — each
     * containing its faces as cells. Badges whichever face's id matches {@code currentCoverFaceId}
     * as the current cover. {@code currentPersonId} is needed to exclude "this person" from the
     * "Not this person…" destination list.
     */
    public void init(List<FaceRecord> faces, Long currentCoverFaceId, long currentPersonId) {
        this.currentPersonId = currentPersonId;
        this.correctionsHappened = false;
        cellsById.clear();
        clusterKeyByFaceId.clear();
        sectionsByKey.clear();
        selectedIds.clear();
        faceSectionsBox.getChildren()
                       .clear();

        Map<Long, List<FaceRecord>> byCluster = new LinkedHashMap<>();
        for (FaceRecord face : faces) {
            long key = face.getClusterId() == null ? UNCLUSTERED_KEY : face.getClusterId();
            byCluster.computeIfAbsent(key, k -> new ArrayList<>())
                     .add(face);
        }

        List<Long> orderedKeys = byCluster.keySet()
                                          .stream()
                                          .sorted(Comparator.comparingLong(k -> k == UNCLUSTERED_KEY ? Long.MAX_VALUE : k))
                                          .toList();

        for (Long key : orderedKeys) {
            List<FaceRecord> sectionFaces = byCluster.get(key);
            String title = key == UNCLUSTERED_KEY
                    ? "Unclustered (" + sectionFaces.size() + ")"
                    : "Prototype #" + key + " (" + sectionFaces.size() + " face" + (sectionFaces.size() == 1 ? "" : "s") + ")";

            FlowPane   sectionFlow = new FlowPane(8, 8);
            TitledPane section     = new TitledPane(title, sectionFlow);
            section.setAnimated(false);
            section.setMaxWidth(Double.MAX_VALUE);
            faceSectionsBox.getChildren()
                           .add(section);
            sectionsByKey.put(key, section);

            for (FaceRecord face : sectionFaces) {
                addCell(face, currentCoverFaceId != null && currentCoverFaceId.equals(face.getId()), sectionFlow, key);
            }
        }
        updateSelectionLabel();
    }

    private void addCell(FaceRecord face, boolean isCover, FlowPane targetFlow, long clusterKey) {
        LoadedFxml<FacePickerCellController> loaded = fxmlLoader.load(FxmlView.FACE_PICKER_CELL, null);
        FacePickerCellController             cell   = loaded.controller();
        cell.bind(face, isCover, this::onFaceChosen, this::onToggleSelect, this::onContextMenuRequested);
        cellsById.put(face.getId(), cell);
        clusterKeyByFaceId.put(face.getId(), clusterKey);
        targetFlow.getChildren()
                  .add(loaded.parent());
    }

    /**
     * Removes one face's cell from whichever section it's in. If that empties the section
     * entirely, the section (its header and now-empty {@link FlowPane}) is removed too, so a
     * fully-moved-away prototype doesn't linger as an empty collapsed group.
     */
    private void removeCell(long faceId) {
        FacePickerCellController cell = cellsById.remove(faceId);
        selectedIds.remove(faceId);
        Long clusterKey = clusterKeyByFaceId.remove(faceId);
        if (cell == null) {
            return;
        }
        Parent parent = cell.cellRoot.getParent();
        if (parent instanceof Pane pane) {
            pane.getChildren()
                .remove(cell.cellRoot);
            if (pane.getChildren()
                    .isEmpty() && clusterKey != null) {
                TitledPane section = sectionsByKey.remove(clusterKey);
                if (section != null) {
                    faceSectionsBox.getChildren()
                                   .remove(section);
                }
            }
        }
    }

    private void onFaceChosen(FaceRecord face) {
        selectedFace = face;
        close();
    }

    private void onToggleSelect(FaceRecord face) {
        long id = face.getId();
        if (selectedIds.contains(id)) {
            selectedIds.remove(id);
            cellsById.get(id)
                     .setSelected(false);
        } else {
            selectedIds.add(id);
            cellsById.get(id)
                     .setSelected(true);
        }
        updateSelectionLabel();
    }

    private void updateSelectionLabel() {
        if (moveSelectedMenuButton != null) {
            moveSelectedMenuButton.setDisable(selectedIds.isEmpty());
        }
        if (selectionCountLabel != null) {
            selectionCountLabel.setText(selectedIds.isEmpty() ? "" : selectedIds.size() + " selected");
        }
    }

    private void clearSelection() {
        for (Long id : Set.copyOf(selectedIds)) {
            FacePickerCellController cell = cellsById.get(id);
            if (cell != null) {
                cell.setSelected(false);
            }
        }
        selectedIds.clear();
        updateSelectionLabel();
    }

    // ── FR1/FR2/FR5 — right-click context menu ────────────────────────────────────────────────

    private void onContextMenuRequested(FaceRecord clickedFace, MouseEvent event) {
        Set<Long> targetIds = (selectedIds.contains(clickedFace.getId()) && selectedIds.size() > 1)
                ? Set.copyOf(selectedIds)
                : Set.of(clickedFace.getId());

        ContextMenu menu = new ContextMenu();

        MenuItem confirmItem = new MenuItem("Confirm (" + targetIds.size() + ")");
        confirmItem.setOnAction(e -> {
            targetIds.forEach(personCorrectionService::confirmFace);
            correctionsHappened = true;
            clearSelection();
        });
        menu.getItems()
            .add(confirmItem);

        MenuItem notThisPersonItem = new MenuItem("Not this person…");
        notThisPersonItem.setOnAction(e -> promptDestinationDialog(targetIds));
        menu.getItems()
            .add(notThisPersonItem);

        MenuItem excludeItem = new MenuItem("Exclude (\"not a person\")");
        excludeItem.setOnAction(e -> confirmAndExclude(targetIds));
        menu.getItems()
            .add(excludeItem);

        Node source = (Node) event.getSource();
        menu.show(source, event.getScreenX(), event.getScreenY());
    }

    private void confirmAndExclude(Set<Long> targetIds) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Mark " + targetIds.size() + " face(s) as \"not a person\"? "
                        + "They'll be removed from clustering entirely.",
                ButtonType.OK, ButtonType.CANCEL);
        alert.setHeaderText("Exclude face" + (targetIds.size() > 1 ? "s" : ""));
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return;
        }
        Set<Long> orphaned = new LinkedHashSet<>();
        for (Long id : targetIds) {
            personCorrectionService.excludeFace(id)
                                   .ifPresent(orphaned::add);
        }
        correctionsHappened = true;
        targetIds.forEach(this::removeCell);
        updateSelectionLabel();
        handleOrphanedPersons(orphaned);
    }

    // ── FR3 — "Move Selected to…" toolbar action ─────────────────────────────────────────────

    @FXML
    public void onMoveSelected() {
        promptDestinationDialog(Set.copyOf(selectedIds));
    }

    /**
     * One row in {@link #promptDestinationDialog}: a label, an optional representative
     * thumbnail, whether it's the similarity-based suggestion, and what to do if chosen.
     */
    private record DestinationOption(String label, Path thumbnailPath, boolean suggested, Runnable onChoose) {
    }

    /**
     * The FR1/FR3 "Not this person…" destination picker — a {@link Dialog} rather than a menu,
     * because picking a destination is a visual-recognition task: the human needs to actually
     * see a face from a prototype to know it's the right one, which a text-only menu can't show
     * well. One row per existing prototype of each eligible person (a representative thumbnail,
     * member count, and — never auto-applied, just a label —
     * {@link PersonCorrectionService#suggestDestinationCluster}'s pick marked "suggested"), a
     * "New prototype for …" row per person, and a "New person…" row.
     */
    private void promptDestinationDialog(Set<Long> targetIds) {
        if (targetIds.isEmpty()) {
            return;
        }
        List<DestinationOption> options = buildDestinationOptions(targetIds);

        Dialog<DestinationOption> dialog = new Dialog<>();
        dialog.setTitle("Move to…");
        dialog.setHeaderText("Choose where to move " + targetIds.size() + " face" + (targetIds.size() > 1 ? "s" : ""));

        ButtonType chooseButtonType = new ButtonType("Choose", ButtonType.OK.getButtonData());
        dialog.getDialogPane()
              .getButtonTypes()
              .addAll(chooseButtonType, ButtonType.CANCEL);

        ListView<DestinationOption> listView = new ListView<>();
        listView.getItems()
                .addAll(options);
        listView.setCellFactory(lv -> new DestinationOptionCell());
        listView.setPrefSize(440, 480);
        listView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && listView.getSelectionModel()
                                                  .getSelectedItem() != null) {
                dialog.setResult(listView.getSelectionModel()
                                         .getSelectedItem());
            }
        });
        dialog.getDialogPane()
              .setContent(listView);

        Node chooseButton = dialog.getDialogPane()
                                  .lookupButton(chooseButtonType);
        chooseButton.setDisable(true);
        listView.getSelectionModel()
                .selectedItemProperty()
                .addListener((obs, old, selected) -> chooseButton.setDisable(selected == null));

        dialog.setResultConverter(bt -> bt == chooseButtonType ? listView.getSelectionModel()
                                                                         .getSelectedItem() : null);

        dialog.showAndWait()
              .ifPresent(chosen -> chosen.onChoose()
                                         .run());
    }

    /**
     * Builds one {@link DestinationOption} per existing prototype of every eligible destination
     * person (everyone except the person currently being viewed, and anyone already merged
     * away), a "New prototype for …" option per person, and a trailing "New person…" option.
     */
    private List<DestinationOption> buildDestinationOptions(Set<Long> targetIds) {
        List<DestinationOption> options = new ArrayList<>();

        List<PersonRecord> candidates = personRepo.findAll()
                                                  .stream()
                                                  .filter(p -> p.getId() != currentPersonId)
                                                  .filter(p -> p.getMergedIntoId() == null)
                                                  .toList();

        for (PersonRecord person : candidates) {
            List<ClusterRecord> clusters = clusterRepo.findByPersonId(person.getId());

            if (clusters.isEmpty()) {
                options.add(new DestinationOption(
                        displayName(person), coverThumbnail(person), false,
                        () -> moveTo(targetIds, person.getId(), null)));
                continue;
            }

            Long suggestedClusterId = personCorrectionService.suggestDestinationCluster(person.getId(), targetIds)
                                                             .map(ClusterRecord::getId)
                                                             .orElse(null);
            for (ClusterRecord cluster : clusters) {
                boolean suggested = cluster.getId()
                                           .equals(suggestedClusterId);
                options.add(new DestinationOption(
                        displayName(person) + " — Prototype #" + cluster.getId()
                                + " (" + cluster.getMemberCount() + " faces)",
                        representativeThumbnail(cluster.getId()), suggested,
                        () -> moveTo(targetIds, person.getId(), cluster.getId())));
            }
            options.add(new DestinationOption(
                    displayName(person) + " — New prototype", coverThumbnail(person), false,
                    () -> moveTo(targetIds, person.getId(), null)));
        }

        options.add(new DestinationOption("New person…", null, false, () -> promptNewPersonAndMove(targetIds)));
        return options;
    }

    /**
     * A thumbnail of any one face currently in {@code clusterId}, or {@code null} if it has none.
     */
    private Path representativeThumbnail(long clusterId) {
        return faceRepo.findByClusterId(clusterId)
                       .stream()
                       .findFirst()
                       .map(FaceRecord::getThumbnailAbsolutePath)
                       .map(Path::of)
                       .orElse(null);
    }

    /**
     * {@code person}'s cover face thumbnail, or {@code null} if they don't have one set.
     */
    private Path coverThumbnail(PersonRecord person) {
        Long coverFaceId = person.getCoverFaceId();
        if (coverFaceId == null) {
            return null;
        }
        return faceRepo.findById(coverFaceId)
                       .map(FaceRecord::getThumbnailAbsolutePath)
                       .map(Path::of)
                       .orElse(null);
    }

    /**
     * Renders one {@link DestinationOption} row: thumbnail (if any) + label, "suggested" starred.
     */
    private static final class DestinationOptionCell extends ListCell<DestinationOption> {
        private final ImageView imageView = new ImageView();
        private final Label     label     = new Label();
        private final HBox      root      = new HBox(10, imageView, label);

        DestinationOptionCell() {
            imageView.setFitWidth(56);
            imageView.setFitHeight(56);
            imageView.setPreserveRatio(true);
            root.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(DestinationOption item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            label.setText(item.label() + (item.suggested() ? "  ★ suggested" : ""));
            imageView.setImage(loadThumbnailQuiet(item.thumbnailPath()));
            setGraphic(root);
        }
    }

    /**
     * Best-effort thumbnail load for the destination dialog — {@code null} in, {@code null} out.
     */
    private static Image loadThumbnailQuiet(Path path) {
        if (path == null) {
            return null;
        }
        try {
            return new Image(path.toUri()
                                 .toString(), 56, 56, true, true, true);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Applies the FR1/FR3 move once the human has picked a destination (person, and — if that
     * person owns more than one prototype — which prototype, or {@code null} for a new one), then
     * surfaces the Q1 "should this now-empty person be deleted?" prompt for anyone orphaned by
     * the move.
     */
    private void moveTo(Set<Long> targetIds, long targetPersonId, Long destinationClusterId) {
        Set<Long> orphaned = personCorrectionService.reassignFacesToExistingPerson(
                targetIds, targetPersonId, destinationClusterId);
        correctionsHappened = true;
        targetIds.forEach(this::removeCell);
        updateSelectionLabel();
        handleOrphanedPersons(orphaned);
    }

    private void promptNewPersonAndMove(Set<Long> targetIds) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setHeaderText("New person");
        dialog.setContentText("Name:");
        Optional<String> name = dialog.showAndWait();
        if (name.isEmpty() || name.get()
                                  .isBlank()) {
            return;
        }
        Set<Long> orphaned = personCorrectionService.reassignFacesToNewPerson(targetIds, name.get()
                                                                                             .trim());
        correctionsHappened = true;
        targetIds.forEach(this::removeCell);
        updateSelectionLabel();
        handleOrphanedPersons(orphaned);
    }

    // ── Q1 — orphaned-person cleanup prompt ───────────────────────────────────────────────────

    /**
     * For each person a move/exclude left owning zero prototypes, asks the user whether to
     * delete that now-empty person and, if confirmed, calls
     * {@link PersonCorrectionService#deleteOrphanedPerson}. This is the only place that decides
     * to delete an orphaned person — the service itself never does.
     */
    private void handleOrphanedPersons(Set<Long> orphanedPersonIds) {
        for (Long personId : orphanedPersonIds) {
            String label = personRepo.findById(personId)
                                     .map(this::displayName)
                                     .orElse("Person #" + personId);
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                    "\"" + label + "\" has no photos left after this move. Delete this person?",
                    ButtonType.OK, ButtonType.CANCEL);
            alert.setHeaderText("Remove empty person");
            Optional<ButtonType> result = alert.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                personCorrectionService.deleteOrphanedPerson(personId);
                eventPublisher.publishEvent(new TreeViewUpdateEvent(this, new TreeViewUpdatePayload.PersonDelete(personId)));
            }
        }
    }

    private String displayName(PersonRecord person) {
        String name = person.getName();
        return (name == null || name.isBlank()) ? ("Person #" + person.getId()) : name;
    }

    @FXML
    public void onCancel() {
        selectedFace = null;
        close();
    }

    private void close() {
        if (stage != null) {
            stage.close();
        }
    }
}
