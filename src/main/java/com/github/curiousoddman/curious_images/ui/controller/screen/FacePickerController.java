package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.dbobj.tables.records.ClusterRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PersonRecord;
import com.github.curiousoddman.curious_images.domain.ai.PersonCorrectionService;
import com.github.curiousoddman.curious_images.model.LoadedFxml;
import com.github.curiousoddman.curious_images.persistence.ClusterRepository;
import com.github.curiousoddman.curious_images.persistence.PersonRepository;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.FxmlView;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * Controller for {@code face_picker.fxml} — a modal grid of every face tagged for a person.
 * Originally just a cover-photo chooser (plain click → pick cover → close, still unchanged —
 * see {@link #onFaceChosen}), this dialog is also the FR1/FR2/FR3/FR5 correction entry point
 * from {@code face-person-correction-requirements.md}:
 * <ul>
 *   <li>Ctrl/Shift-click a face to add it to a multi-selection (FR3), without closing the
 *       dialog or touching the cover face.</li>
 *   <li>Right-click a face for "Not this person…" (FR1), "Confirm" (FR2), or "Exclude" (FR5).
 *       If the clicked face is part of an active multi-selection, the action applies to the
 *       whole selection; otherwise it applies to just that one face. "Not this person…" for a
 *       destination person who already owns multiple prototypes opens a submenu so the human
 *       picks which prototype the faces join (or starts a new one) — see
 *       {@link PersonCorrectionService#suggestDestinationCluster} for the suggestion shown
 *       there.</li>
 *   <li>"Move Selected to…" in the toolbar does the same "Not this person…" prompt, explicitly
 *       scoped to the current multi-selection (FR3's "Move selected to…" toolbar action).</li>
 * </ul>
 * Reassigning or excluding a face removes it from this grid immediately — since this dialog only
 * ever shows one person's faces, a face that just left that person no longer belongs here. If a
 * move/exclude leaves some other person owning zero prototypes, this controller also prompts to
 * delete that now-empty person (see {@link #handleOrphanedPersons}) — the correction service
 * itself never deletes a person on its own initiative.
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

    private final FxmlLoader              fxmlLoader;
    private final PersonRepository        personRepo;
    private final ClusterRepository       clusterRepo;
    private final PersonCorrectionService personCorrectionService;

    @FXML
    public FlowPane   faceFlowPane;
    @FXML
    public MenuButton moveSelectedMenuButton;
    @FXML
    public Label      selectionCountLabel;

    private Stage      stage;
    private FaceRecord selectedFace;
    private long       currentPersonId;
    private boolean    correctionsHappened;

    private final Map<Long, FaceRecord>               facesById   = new LinkedHashMap<>();
    private final Map<Long, FacePickerCellController> cellsById   = new LinkedHashMap<>();
    private final Set<Long>                           selectedIds = new LinkedHashSet<>();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // Grid contents depend on which person is being edited, so they're populated in init()
        // rather than here.
        if (moveSelectedMenuButton != null) {
            moveSelectedMenuButton.setOnShowing(e -> populateMenu(moveSelectedMenuButton.getItems(), selectedIds));
            moveSelectedMenuButton.setDisable(true);
        }
        updateSelectionLabel();
    }

    /**
     * Must be called by the owner ({@code stage.setScene(...)} etc. happens outside this
     * controller) so {@link #onCancel} and a face click can close the dialog.
     */
    public void setStage(Stage stage) {
        this.stage = stage;
    }

    /**
     * The face the user clicked, or {@code null} if they cancelled/closed the dialog without
     * choosing one. Only meaningful after {@code stage.showAndWait()} has returned.
     */
    public FaceRecord getSelectedFace() {
        return selectedFace;
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
     * Populates the grid with one cell per face, badging whichever face's id matches
     * {@code currentCoverFaceId} as the current cover. {@code currentPersonId} is needed to
     * exclude "this person" from the "Not this person…" destination list.
     */
    public void init(List<FaceRecord> faces, Long currentCoverFaceId, long currentPersonId) {
        this.currentPersonId = currentPersonId;
        this.correctionsHappened = false;
        facesById.clear();
        cellsById.clear();
        selectedIds.clear();
        faceFlowPane.getChildren()
                    .clear();
        for (FaceRecord face : faces) {
            addCell(face, currentCoverFaceId != null && currentCoverFaceId.equals(face.getId()));
        }
        updateSelectionLabel();
    }

    private void addCell(FaceRecord face, boolean isCover) {
        LoadedFxml<FacePickerCellController> loaded = fxmlLoader.load(FxmlView.FACE_PICKER_CELL, null);
        FacePickerCellController             cell   = loaded.controller();
        cell.bind(face, isCover, this::onFaceChosen, this::onToggleSelect, this::onContextMenuRequested);
        facesById.put(face.getId(), face);
        cellsById.put(face.getId(), cell);
        faceFlowPane.getChildren()
                    .add(loaded.parent());
    }

    private void removeCell(long faceId) {
        FacePickerCellController cell = cellsById.remove(faceId);
        facesById.remove(faceId);
        selectedIds.remove(faceId);
        if (cell != null) {
            faceFlowPane.getChildren()
                        .remove(cell.cellRoot);
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

        Menu notThisPersonMenu = new Menu("Not this person…");
        populateMenu(notThisPersonMenu.getItems(), targetIds);
        menu.getItems()
            .add(notThisPersonMenu);

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
    // Wired via moveSelectedMenuButton.setOnShowing(...) in initialize(), which lazily rebuilds
    // the menu from the current selection each time the button is opened.

    /**
     * Fills {@code items} with one entry per eligible destination person (everyone except the
     * person currently being viewed, and anyone already merged away), followed by a "New
     * person…" entry — this is the FR1/FR3 "Not this person…" picker, reused for both the
     * per-face context menu and the toolbar "Move Selected to…" button.
     * <p>
     * A destination person who already owns one or more prototypes gets a submenu instead of a
     * plain item: the human explicitly picks which prototype the moved faces join (or starts a
     * new one for that person). {@link PersonCorrectionService#suggestDestinationCluster} is used
     * only to label the most likely candidate — it is never auto-applied.
     */
    private void populateMenu(List<MenuItem> items, Set<Long> targetIds) {
        items.clear();
        List<PersonRecord> candidates = personRepo.findAll()
                                                  .stream()
                                                  .filter(p -> p.getId() != currentPersonId)
                                                  .filter(p -> p.getMergedIntoId() == null)
                                                  .toList();
        for (PersonRecord person : candidates) {
            items.add(buildPersonMenuItem(person, targetIds));
        }
        if (!candidates.isEmpty()) {
            items.add(new SeparatorMenuItem());
        }
        MenuItem newPersonItem = new MenuItem("New person…");
        newPersonItem.setOnAction(e -> promptNewPersonAndMove(targetIds));
        items.add(newPersonItem);
    }

    /**
     * One menu entry for a single destination person. If they don't own any prototype yet, a
     * plain item that seeds their first one directly. Otherwise a submenu: one item per existing
     * prototype (the suggested one labeled as such), a separator, then "New prototype for …".
     */
    private MenuItem buildPersonMenuItem(PersonRecord person, Set<Long> targetIds) {
        List<ClusterRecord> clusters = clusterRepo.findByPersonId(person.getId());
        if (clusters.isEmpty()) {
            MenuItem item = new MenuItem(displayName(person));
            item.setOnAction(e -> moveTo(targetIds, person.getId(), null));
            return item;
        }

        Long suggestedClusterId = personCorrectionService.suggestDestinationCluster(person.getId(), targetIds)
                                                          .map(ClusterRecord::getId)
                                                          .orElse(null);

        Menu personMenu = new Menu(displayName(person));
        for (ClusterRecord cluster : clusters) {
            boolean isSuggested = cluster.getId()
                                         .equals(suggestedClusterId);
            MenuItem clusterItem = new MenuItem(
                    "Prototype #" + cluster.getId() + " (" + cluster.getMemberCount() + " faces)"
                            + (isSuggested ? "  — suggested" : ""));
            clusterItem.setOnAction(e -> moveTo(targetIds, person.getId(), cluster.getId()));
            personMenu.getItems()
                      .add(clusterItem);
        }
        personMenu.getItems()
                  .add(new SeparatorMenuItem());
        MenuItem newPrototypeItem = new MenuItem("New prototype for " + displayName(person));
        newPrototypeItem.setOnAction(e -> moveTo(targetIds, person.getId(), null));
        personMenu.getItems()
                  .add(newPrototypeItem);
        return personMenu;
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
            // FIXME: deleting a person should remove tree item immediately
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                    "\"" + label + "\" has no photos left after this move. Delete this person?",
                    ButtonType.OK, ButtonType.CANCEL);
            alert.setHeaderText("Remove empty person");
            Optional<ButtonType> result = alert.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                personCorrectionService.deleteOrphanedPerson(personId);
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
