package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PersonRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ThumbnailRecord;
import com.github.curiousoddman.curious_images.domain.ai.PersonCorrectionService;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.PersonService;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.ThumbnailUtils;
import com.github.curiousoddman.curious_images.event.model.PersonRenamedEvent;
import com.github.curiousoddman.curious_images.event.model.ThumbnailsReadyEvent;
import com.github.curiousoddman.curious_images.model.LoadedFxml;
import com.github.curiousoddman.curious_images.persistence.FaceRepository;
import com.github.curiousoddman.curious_images.persistence.PersonRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoRepository;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.FxmlView;
import com.github.curiousoddman.curious_images.ui.controller.custom.PhotoCellController;
import com.github.curiousoddman.curious_images.ui.controller.custom.PhotoGridRowController;
import com.github.curiousoddman.curious_images.ui.nodes.photogrid.PhotoGridCallbacks;
import com.github.curiousoddman.curious_images.ui.nodes.photogrid.PhotoGridRow;
import com.github.curiousoddman.curious_images.ui.nodes.photogrid.PhotoRowCell;
import com.github.curiousoddman.curious_images.util.async.DelayedAction;
import com.github.curiousoddman.curious_images.util.async.jobs.JobManager;
import javafx.beans.value.ObservableValue;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.stage.Modality;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.curiousoddman.curious_images.ui.controller.screen.DuplicatesController.getPhotoDetailsText;
import static com.github.curiousoddman.curious_images.ui.controller.screen.FacePickerCellController.loadImage;
import static com.github.curiousoddman.curious_images.util.HumanReadableUtils.size;
import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static com.sun.javafx.util.Utils.runOnFxThread;

/**
 * Controller for {@code person_detail.fxml}.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Display and let the user pick a representative face thumbnail.</li>
 *   <li>Show / inline-edit Name, Date of Birth, and free-form Notes.</li>
 *   <li>Build an age-album tree (one leaf per year the person appeared in photos,
 *       plus an "Undated" leaf) and populate the photo grid for the selected leaf.</li>
 * </ul>
 *
 * <h3>Editing UX</h3>
 * Every field (Name, DoB, Notes) starts read-only with a transparent border so it
 * looks like plain text.  Double-clicking activates edit mode: the border becomes
 * visible and the field gains focus.  Pressing Enter (or Tab-out for the TextArea)
 * commits the value; Escape cancels.
 */
@Lazy
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonDetailController implements Initializable, PhotoGridCallbacks {

    /**
     * Thumbnail files are decoded at most at this size regardless of the slider's actual value —
     * matches {@code thumbnailSizeSlider}'s max (see person_detail.fxml) so JavaFX never decodes
     * more pixels than the grid could ever display. Mirrors {@code LibraryController}'s constant
     * of the same name.
     */
    private static final int MAX_THUMBNAIL_DECODE_SIZE = 320;

    // Heuristics used to turn "viewport width" + "thumbnail size" into "columns per row" / "row
    // height" for the virtualized grid — see recomputeGridMetrics(). Same values/roles as
    // LibraryController's identically-named constants.
    private static final double CELL_HPADDING         = 12.0; // photo_cell.fxml left+right padding
    private static final double ROW_HGAP              = 8.0;  // photo_grid_row.fxml HBox spacing
    private static final double GRID_HPADDING         = 20.0; // ListView left+right padding
    private static final double SCROLLBAR_ALLOWANCE   = 16.0; // room for the ListView's own scrollbar
    private static final double LABEL_HEIGHT_ESTIMATE = 40.0; // wrapped filename label, ~2 lines
    private static final double ROW_VGAP              = 8.0;  // vertical gap between grid rows

    private static final int GRID_METRICS_DEBOUNCE_MS  = 150;
    private static final int THUMBNAIL_GEN_DEBOUNCE_MS = 150;

    // ── Injected services ─────────────────────────────────────────────────────

    private final PersonRepository          personRepository;
    private final FaceRepository            faceRepository;
    private final PhotoRepository           photoRepository;
    private final ThumbnailRepository       thumbnailRepository;
    private final JobManager                jobManager;
    private final FxmlLoader                fxmlLoader;
    private final PersonCorrectionService   personCorrectionService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final PersonService             personService;

    // ── FXML nodes ────────────────────────────────────────────────────────────

    @FXML
    public TitledPane             profilePane;
    @FXML
    public ImageView              faceImageView;
    @FXML
    public ProgressIndicator      faceLoadIndicator;
    @FXML
    public Button                 browseFacesButton;
    @FXML
    public Button                 mergeIntoButton;
    @FXML
    public TextField              nameField;
    @FXML
    public TextField              dobField;
    @FXML
    public TextArea               notesArea;
    @FXML
    public Label                  editHintLabel;
    @FXML
    public Slider                 thumbnailSizeSlider;
    @FXML
    public Label                  photoCountLabel;
    @FXML
    public TreeView<String>       ageAlbumTree;
    @FXML
    public ListView<PhotoGridRow> photoGridListView;

    // ── State ─────────────────────────────────────────────────────────────────

    private Image        noImageAvailable;
    private PersonRecord currentPerson;

    /**
     * All face records for the current person, in stable order.
     */
    private List<FaceRecord> allFaces = List.of();

    /**
     * Index into {@link #allFaces} for the face currently shown as the profile thumbnail.
     * Always kept in sync with the saved cover face — set on load (see
     * {@link #pickInitialFaceIndex}) and again whenever the user picks a new cover via the
     * "Browse faces…" dialog (see {@link #onBrowseFaces}).
     */
    private int currentFaceIndex = 0;

    /**
     * Photos grouped by year (calendar year of capture_date), ordered by date taken.
     * Key {@code null} = undated.
     */
    private Map<Integer, List<PhotoRecord>> photosByYear = new LinkedHashMap<>();

    /**
     * Guards against a stale background lookup (person load, or a row's thumbnail lookup)
     * overwriting state from a newer person load or age-album selection. Mirrors
     * {@code LibraryController#selectionGeneration}.
     */
    private final AtomicLong selectionGeneration = new AtomicLong();

    /**
     * The full, ordered photo set for the currently-selected age-album leaf. The grid is fully
     * virtualized (see {@link PhotoRowCell}), so all of it is "in" the grid immediately; only the
     * rows actually scrolled into view ever get a live cell or a thumbnail lookup.
     */
    private List<PhotoRecord>  currentPhotos  = List.of();
    private Map<Long, Integer> photoIndexById = Map.of();

    /**
     * Current column count for the grid, so {@link #recomputeGridMetrics} can tell whether a
     * resize/slider change actually needs the rows regrouping.
     */
    private int lastColumns = -1;

    /**
     * Photo-id → cell controller for every currently *visible* cell — populated/cleared by
     * {@link #onRowShown}/{@link #onRowHidden} as rows scroll in and out of view. Used by
     * {@link #onThumbnailsReady} to swap a placeholder for the real thumbnail on any cell that's
     * still on-screen.
     */
    private final Map<Long, PhotoCellController> visiblePhotoCells = new HashMap<>();

    /**
     * Photo IDs collected from visible rows that turned out to have no cached thumbnail yet,
     * batched up and flushed as a single {@code submitThumbnailGenerationJob} call after a short
     * debounce — see {@link #queueThumbnailGeneration}.
     */
    private final Set<Long>     pendingThumbnailGenIds = new HashSet<>();
    private final DelayedAction thumbnailGenDebounce   = new DelayedAction(THUMBNAIL_GEN_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    private final DelayedAction gridMetricsDebounce    = new DelayedAction(GRID_METRICS_DEBOUNCE_MS, TimeUnit.MILLISECONDS);

    private static final String EDIT_STYLE_ACTIVE =
            "-fx-background-color: -fx-control-inner-background; -fx-border-color: #aaaaaa; -fx-border-radius: 3;";
    private static final String EDIT_STYLE_IDLE   =
            "-fx-background-color: transparent; -fx-border-color: transparent;";

    // ── Initialisation ────────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        noImageAvailable = new Image(
                Objects.requireNonNull(getClass().getResourceAsStream("/img/noimage.png")));

        wireEditableField(nameField, this::commitName);
        wireEditableTextArea(notesArea, this::commitNotes);
        wireEditableField(dobField, this::commitDob);

        ageAlbumTree.getSelectionModel()
                    .selectedItemProperty()
                    .addListener((obs, oldItem, newItem) -> onAgeAlbumSelected(newItem));

        // Virtualized photo grid — same approach as LibraryController's photoGridListView: only
        // enough PhotoRowCells to cover the viewport (plus a small buffer) are ever live.
        photoGridListView.setCellFactory(lv -> new PhotoRowCell(this));
        photoGridListView.setFocusTraversable(false);

        photoGridListView.widthProperty()
                         .addListener((obs, oldValue, newValue) ->
                                 gridMetricsDebounce.reSchedule(() -> recomputeGridMetrics(false)));
        thumbnailSizeSlider.valueProperty()
                           .addListener((obs, oldValue, newValue) ->
                                   gridMetricsDebounce.reSchedule(() -> recomputeGridMetrics(false)));
    }

    // ── Public API called by LibraryController ────────────────────────────────

    /**
     * Load everything for the given person.  Safe to call from any thread
     * (kicks off background work internally).
     */
    public void loadPerson(long personId) {
        long myGeneration = selectionGeneration.incrementAndGet();
        runOnDaemonThread("LoadPerson", () -> {
            Optional<PersonRecord> opt = personRepository.findById(personId);
            if (opt.isEmpty()) {
                log.warn("Person {} not found", personId);
                return;
            }
            PersonRecord     person     = opt.get();
            List<FaceRecord> faces      = faceRepository.findByPersonId(personId);
            int              startIndex = pickInitialFaceIndex(person, faces);

            // Collect all photos for this person, deduplicated, ordered by capture date
            List<Long> photoIds = new ArrayList<>(
                    new LinkedHashSet<>(
                            faces.stream()
                                 .map(FaceRecord::getPhotoId)
                                 .toList()));
            List<PhotoRecord> photos = photoIds.stream()
                                               .map(id -> photoRepository.findById(id)
                                                                         .orElse(null))
                                               .filter(Objects::nonNull)
                                               .sorted(PersonDetailController::compareByDate)
                                               .toList();

            // Thumbnails are no longer prefetched in bulk here — the virtualized grid looks them
            // up (and triggers generation for whatever's missing) per visible row, see onRowShown.
            Map<Integer, List<PhotoRecord>> byYear = groupByYear(photos);

            runOnFxThread(() -> {
                if (myGeneration != selectionGeneration.get()) {
                    return; // a newer loadPerson() call has since superseded this one
                }
                currentPerson = person;
                allFaces = faces;
                currentFaceIndex = startIndex;
                photosByYear = byYear;

                populateProfileFields(person);
                refreshFacePicker();
                buildAgeAlbumTree(byYear);
                // Select "All" (or first year) automatically
                if (!ageAlbumTree.getRoot()
                                 .getChildren()
                                 .isEmpty()) {
                    ageAlbumTree.getSelectionModel()
                                .selectFirst();
                }
            });
        });
    }

    // ── Profile fields ────────────────────────────────────────────────────────

    private void populateProfileFields(PersonRecord person) {
        nameField.setText(person.getName() != null ? person.getName() : "");
        dobField.setText(person.getDateOfBirth() != null
                ? person.getDateOfBirth()
                        .toString() : "");
        notesArea.setText(person.getNotes() != null ? person.getNotes() : "");
    }

    // ── Inline editing wiring ─────────────────────────────────────────────────

    /**
     * Makes a {@link TextField} behave as a read-only label until double-clicked,
     * then commits on Enter or focus-loss, cancels on Escape.
     */
    private void wireEditableField(TextField field, Runnable onCommit) {
        field.setStyle(EDIT_STYLE_IDLE);
        field.setEditable(false);

        field.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                activateField(field);
            }
        });

        String[] savedValue = {field.getText()};

        field.focusedProperty()
             .addListener((obs, wasFocused, isFocused) -> {
                 if (wasFocused && !isFocused) {
                     // commit on focus-loss (e.g. Tab-out)
                     deactivateField(field);
                     onCommit.run();
                 }
                 if (isFocused) {
                     savedValue[0] = field.getText();
                 }
             });

        field.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                deactivateField(field);
                onCommit.run();
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                field.setText(savedValue[0]);
                deactivateField(field);
                e.consume();
            }
        });
    }

    /**
     * Same for {@link TextArea}: double-click activates, Escape cancels,
     * focus-loss commits (Enter inserts a newline as normal).
     */
    private void wireEditableTextArea(TextArea area, Runnable onCommit) {
        area.setStyle(EDIT_STYLE_IDLE);
        area.setEditable(false);

        area.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                activateArea(area);
            }
        });

        String[] savedValue = {area.getText()};

        area.focusedProperty()
            .addListener((obs, wasFocused, isFocused) -> {
                if (wasFocused && !isFocused) {
                    deactivateArea(area);
                    onCommit.run();
                }
                if (isFocused) {
                    savedValue[0] = area.getText();
                }
            });

        area.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                area.setText(savedValue[0]);
                deactivateArea(area);
                e.consume();
            }
        });
    }

    private void activateField(TextField field) {
        field.setEditable(true);
        field.setStyle(EDIT_STYLE_ACTIVE);
        field.requestFocus();
        field.selectAll();
    }

    private void deactivateField(TextField field) {
        field.setEditable(false);
        field.setStyle(EDIT_STYLE_IDLE);
    }

    private void activateArea(TextArea area) {
        area.setEditable(true);
        area.setStyle(EDIT_STYLE_ACTIVE);
        area.requestFocus();
    }

    private void deactivateArea(TextArea area) {
        area.setEditable(false);
        area.setStyle(EDIT_STYLE_IDLE);
    }

    // ── Commit handlers ───────────────────────────────────────────────────────

    private void commitName() {
        if (currentPerson == null) {
            return;
        }
        String value = nameField.getText()
                                .trim();
        runOnDaemonThread("Commit Name", () -> {
            try {
                personRepository.updateNameQuery(currentPerson.getId(), value, LocalDateTime.now())
                                .execute();
                currentPerson.setName(value);
                applicationEventPublisher.publishEvent(new PersonRenamedEvent(this, currentPerson.getId(), value));
            } catch (Exception ex) {
                log.error("Failed to save name for person {}", currentPerson.getId(), ex);
            }
        });
    }

    private void commitDob() {
        if (currentPerson == null) {
            return;
        }
        String raw = dobField.getText()
                             .trim();
        LocalDate parsed = null;
        if (!raw.isEmpty()) {
            try {
                parsed = LocalDate.parse(raw);
            } catch (DateTimeParseException ex) {
                // Restore last valid value shown
                dobField.setText(currentPerson.getDateOfBirth() != null
                        ? currentPerson.getDateOfBirth()
                                       .toString() : "");
                log.warn("Invalid date entered: '{}'", raw);
                return;
            }
        }
        final LocalDate dob = parsed;
        runOnDaemonThread("Update DoB", () -> {
            try {
                personRepository.updateDob(currentPerson.getId(), dob, LocalDateTime.now());
                currentPerson.setDateOfBirth(dob);
                // Rebuild age albums if DoB changed (ages recalculate)
                Map<Integer, List<PhotoRecord>> byYear = photosByYear; // already collected
                runOnFxThread(() -> buildAgeAlbumTree(byYear));
            } catch (Exception ex) {
                log.error("Failed to save DoB for person {}", currentPerson.getId(), ex);
            }
        });
    }

    private void commitNotes() {
        if (currentPerson == null) {
            return;
        }
        String value = notesArea.getText();
        runOnDaemonThread("CommitNotes", () -> {
            try {
                personRepository.updateNotes(currentPerson.getId(), value, LocalDateTime.now());
                currentPerson.setNotes(value);
            } catch (Exception ex) {
                log.error("Failed to save notes for person {}", currentPerson.getId(), ex);
            }
        });
    }

    // ── Face picker ───────────────────────────────────────────────────────────

    private int pickInitialFaceIndex(PersonRecord person, List<FaceRecord> faces) {
        if (faces.isEmpty()) {
            return 0;
        }
        // Prefer the saved cover face
        if (person.getCoverFaceId() != null) {
            for (int i = 0; i < faces.size(); i++) {
                if (faces.get(i)
                         .getId()
                         .equals(person.getCoverFaceId())) {
                    return i;
                }
            }
        }
        // Fall back to a random face
        return new Random().nextInt(faces.size());
    }

    private void refreshFacePicker() {
        int total = allFaces.size();
        browseFacesButton.setDisable(total == 0);

        if (total == 0) {
            faceImageView.setImage(noImageAvailable);
            return;
        }

        FaceRecord face = allFaces.get(currentFaceIndex);
        loadFaceThumbnail(face);
    }

    private void loadFaceThumbnail(FaceRecord face) {
        faceLoadIndicator.setVisible(true);
        faceImageView.setImage(null);

        runOnDaemonThread("", () -> loadImage(faceImageView, faceLoadIndicator, face));
    }

    /**
     * Opens the modal face-picker grid (see {@code face_picker.fxml} / {@link FacePickerController})
     * so the user can jump straight to a new cover face instead of clicking through them one at a
     * time. Blocks (it's application-modal) until the dialog closes; if the user clicked a face
     * rather than cancelling, applies it as the new cover.
     */
    @FXML
    public void onBrowseFaces() {
        if (currentPerson == null || allFaces.isEmpty()) {
            return;
        }
        try {
            LoadedFxml<FacePickerController> loaded     = fxmlLoader.load(FxmlView.FACE_PICKER, null);
            FacePickerController             controller = loaded.controller();
            controller.init(allFaces, currentPerson.getCoverFaceId(), currentPerson.getId());

            Stage stage = new Stage();
            stage.setTitle("Browse Faces");
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.initOwner(faceImageView.getScene()
                                         .getWindow());
            stage.setScene(new Scene(loaded.parent()));
            controller.setStage(stage);
            stage.showAndWait();

            FaceRecord chosen = controller.getSelectedFace();
            if (chosen != null) {
                applyCoverFace(chosen.getId());
            }
            if (controller.didCorrectionsHappen()) {
                // Faces may have been reassigned/excluded out from under this person — reload
                // everything (face list, photo grid, age albums) rather than patching in place.
                loadPerson(currentPerson.getId());
            }
        } catch (Exception ex) {
            log.error("Failed to open face picker", ex);
        }
    }

    /**
     * FR4: merges the person currently being viewed into another person the user picks. The
     * source person's row survives as a redirect (see {@code PersonCorrectionService#mergePerson})
     * so old references still resolve; this view navigates to the target afterward since "this
     * person" is no longer a thing you'd keep browsing.
     */
    @FXML
    public void onMergeInto() {
        if (currentPerson == null) {
            return;
        }
        List<PersonRecord> candidates = personRepository.findAll()
                                                        .stream()
                                                        .filter(p -> !p.getId()
                                                                       .equals(currentPerson.getId()))
                                                        .filter(p -> p.getMergedIntoId() == null)
                                                        .toList();
        if (candidates.isEmpty()) {
            log.info("No other person available to merge {} into", currentPerson.getId());
            return;
        }

        Map<String, Long> idByLabel = new LinkedHashMap<>();
        for (PersonRecord p : candidates) {
            String name = (p.getName() == null || p.getName()
                                                   .isBlank()) ? ("Person #" + p.getId()) : p.getName();
            idByLabel.put(name, p.getId());
        }

        ChoiceDialog<String> pick = new ChoiceDialog<>(null, idByLabel.keySet());
        pick.setHeaderText("Merge \"" + nameField.getText() + "\" into…");
        pick.setContentText("Target person:");
        Optional<String> chosenLabel = pick.showAndWait();
        if (chosenLabel.isEmpty()) {
            return;
        }
        long targetPersonId = idByLabel.get(chosenLabel.get());

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Merge \"" + nameField.getText() + "\" into \"" + chosenLabel.get() + "\"? "
                        + "All of this person's face groups will become part of the target. "
                        + "This can't be undone from the UI.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText("Merge people");
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return;
        }

        long sourcePersonId = currentPerson.getId();
        runOnDaemonThread("ApplyCoverFace", () -> {
            try {
                personCorrectionService.mergePerson(sourcePersonId, targetPersonId);
                runOnFxThread(() -> loadPerson(targetPersonId));
            } catch (Exception ex) {
                log.error("Failed to merge person {} into {}", sourcePersonId, targetPersonId, ex);
            }
        });
    }

// ── Age-album tree ────────────────────────────────────────────────────────

    /**
     * Groups photos by calendar year of capture_date.
     * Key {@code null} collects undated photos.
     */
    private static Map<Integer, List<PhotoRecord>> groupByYear(List<PhotoRecord> photos) {
        Map<Integer, List<PhotoRecord>> map = new LinkedHashMap<>();
        for (PhotoRecord p : photos) {
            Integer year = (p.getCaptureDate() != null)
                    ? p.getCaptureDate()
                       .getYear()
                    : null;
            map.computeIfAbsent(year, k -> new ArrayList<>())
               .add(p);
        }
        return map;
    }

    /**
     * Builds the left-hand age-album {@link TreeView}.
     * <p>
     * If {@link PersonRecord#getDateOfBirth()} is set, the year node label shows
     * the person's age in that year alongside the photo count.
     * If DoB is not set, only the year and count are shown.
     * Undated photos get a dedicated leaf at the bottom.
     */
    private void buildAgeAlbumTree(Map<Integer, List<PhotoRecord>> byYear) {
        TreeItem<String> root = new TreeItem<>("root");
        root.setExpanded(false);

        LocalDate dob = (currentPerson != null) ? currentPerson.getDateOfBirth() : null;

        // Collect dated years first (sorted ascending), then undated
        List<Integer> sortedYears = byYear.keySet()
                                          .stream()
                                          .filter(Objects::nonNull)
                                          .sorted()
                                          .toList();

        // "All photos" node at the top
        int totalDated = sortedYears.stream()
                                    .mapToInt(y -> byYear.get(y)
                                                         .size())
                                    .sum();
        int undatedCount = byYear.containsKey(null) ? byYear.get(null)
                                                            .size() : 0;
        int total = totalDated + undatedCount;
        root.getChildren()
            .add(new TreeItem<>("All (" + total + ")"));

        for (Integer year : sortedYears) {
            int count = byYear.get(year)
                              .size();
            String label;
            if (dob != null) {
                int age = year - dob.getYear();
                label = year + "  ·  age " + age + "  (" + count + ")";
            } else {
                label = year + "  (" + count + ")";
            }
            root.getChildren()
                .add(new TreeItem<>(label));
        }

        if (undatedCount > 0) {
            root.getChildren()
                .add(new TreeItem<>("Undated (" + undatedCount + ")"));
        }

        ageAlbumTree.setRoot(root);
        ageAlbumTree.setShowRoot(false);
    }

    private void onAgeAlbumSelected(TreeItem<String> item) {
        if (item == null || currentPerson == null) {
            return;
        }

        String            label = item.getValue();
        List<PhotoRecord> toShow;

        if (label.startsWith("All")) {
            // flatten all years + undated
            toShow = photosByYear.values()
                                 .stream()
                                 .flatMap(List::stream)
                                 .sorted(PersonDetailController::compareByDate)
                                 .toList();
        } else if (label.startsWith("Undated")) {
            toShow = photosByYear.getOrDefault(null, List.of());
        } else {
            // label starts with the 4-digit year
            int year = Integer.parseInt(label.substring(0, 4));
            toShow = photosByYear.getOrDefault(year, List.of());
        }

        populatePhotoGrid(toShow);
    }

// ── Photo grid (virtualized) ────────────────────────────────────────────────

    /**
     * Applies a freshly-selected, fully-ordered photo set to the grid — mirrors
     * {@code LibraryController#populatePhotoGrid}. The entire set is handed to the (virtualized)
     * {@code ListView} right away; only {@link #recomputeGridMetrics} decides how it's chunked
     * into rows, and only rows that actually scroll into view ever get a live cell or a
     * thumbnail lookup (which also triggers on-demand generation for anything missing — see
     * {@link #onRowShown}).
     */
    private void populatePhotoGrid(List<PhotoRecord> photos) {
        selectionGeneration.incrementAndGet();
        visiblePhotoCells.clear();
        pendingThumbnailGenIds.clear();
        currentPhotos = photos;
        photoIndexById = buildIndexMap(photos);
        photoCountLabel.setText(photos.size() + " photo" + (photos.size() == 1 ? "" : "s"));
        recomputeGridMetrics(true); // force a regroup even if the column count is unchanged
    }

    private static Map<Long, Integer> buildIndexMap(List<PhotoRecord> photos) {
        Map<Long, Integer> map = new HashMap<>(photos.size() * 2);
        for (int i = 0; i < photos.size(); i++) {
            map.put(photos.get(i)
                          .getId(), i);
        }
        return map;
    }

    /**
     * Recomputes columns-per-row (from viewport width) and row height (from thumbnail size), and
     * updates {@link ListView#setFixedCellSize}. Only actually regroups {@link #currentPhotos}
     * into {@link PhotoGridRow}s if the column count changed or {@code force} is set. Mirrors
     * {@code LibraryController#recomputeGridMetrics}.
     */
    private void recomputeGridMetrics(boolean force) {
        double viewportWidth = photoGridListView.getWidth();
        if (viewportWidth <= 0) {
            return; // not laid out yet; the width listener will fire again once it is
        }
        double thumbSize = thumbnailSizeSlider.getValue();
        double cellWidth = thumbSize + CELL_HPADDING + ROW_HGAP;
        int    columns   = Math.max(1, (int) Math.floor((viewportWidth - GRID_HPADDING - SCROLLBAR_ALLOWANCE) / cellWidth));
        double rowHeight = thumbSize + LABEL_HEIGHT_ESTIMATE + ROW_VGAP;

        photoGridListView.setFixedCellSize(rowHeight);

        if (force || columns != lastColumns) {
            lastColumns = columns;
            regroupIntoRows(columns);
        }
    }

    private void regroupIntoRows(int columns) {
        List<PhotoGridRow> rows = new ArrayList<>();
        for (int i = 0; i < currentPhotos.size(); i += columns) {
            rows.add(new PhotoGridRow(currentPhotos.subList(i, Math.min(i + columns, currentPhotos.size()))));
        }
        photoGridListView.getItems()
                         .setAll(rows);
    }

// ── PhotoGridCallbacks — the narrow surface PhotoRowCell/PhotoGridRowController use ────────

    @Override
    public ObservableValue<Number> thumbnailSizeProperty() {
        return thumbnailSizeSlider.valueProperty();
    }

    @Override
    public void onPhotoClicked(PhotoRecord photo) {
        Integer idx = photoIndexById.get(photo.getId());
        if (idx != null) {
            openSlideshow(currentPhotos, idx);
        }
    }

    @Override
    public String tooltipTextFor(PhotoRecord photo) {
        return buildPhotoDetailsText(photo);
    }

    /**
     * A row just became visible (or was re-flowed while still visible). Registers its photos in
     * {@link #visiblePhotoCells} so {@link #onThumbnailsReady} can reach them directly, then looks
     * up thumbnails for exactly these photo IDs on a background thread and applies them — any
     * photo with no real thumbnail yet is queued for on-demand generation. Mirrors
     * {@code LibraryController#onRowShown}.
     */
    @Override
    public void onRowShown(PhotoGridRowController row, List<PhotoRecord> photos) {
        RowInfo rowInfo = getRowInfo(row, photos, visiblePhotoCells, selectionGeneration);

        runOnDaemonThread("Row Shown", () -> {
            Map<Long, ThumbnailRecord> thumbs = thumbnailRepository.findByPhotoIds(rowInfo.ids());
            runOnFxThread(() -> {
                if (rowInfo.myGeneration() != selectionGeneration.get() || rowInfo.myShowToken() != row.getShowToken()) {
                    return; // selection changed, or this row now shows different photos — discard
                }
                List<Long> missing = new ArrayList<>();
                for (PhotoRecord photo : photos) {
                    ThumbnailRecord thumbnail = thumbs.get(photo.getId());
                    if (thumbnail != null && hasCachedFile(thumbnail)) {
                        row.applyImage(photo, loadThumbnailImage(thumbnail));
                    } else {
                        missing.add(photo.getId()); // no real thumbnail yet
                    }
                }
                if (!missing.isEmpty()) {
                    queueThumbnailGeneration(missing);
                }
            });
        });
    }

    public static RowInfo getRowInfo(PhotoGridRowController row, List<PhotoRecord> photos, Map<Long, PhotoCellController> visiblePhotoCells, AtomicLong selectionGeneration) {
        for (PhotoCellController cell : row.getCellControllers()) {
            PhotoRecord shown = cell.getCurrentPhoto();
            if (shown != null) {
                visiblePhotoCells.put(shown.getId(), cell);
            }
        }

        long myGeneration = selectionGeneration.get();
        long myShowToken  = row.getShowToken();
        List<Long> ids = photos.stream()
                               .map(PhotoRecord::getId)
                               .toList();
        return new RowInfo(myGeneration, myShowToken, ids);
    }

    public record RowInfo(long myGeneration, long myShowToken, List<Long> ids) {}

    @Override
    public void onRowHidden(PhotoGridRowController row, List<PhotoRecord> previousPhotos) {
        for (PhotoRecord photo : previousPhotos) {
            visiblePhotoCells.remove(photo.getId());
        }
    }

    /**
     * Batches up photo IDs missing a real thumbnail and flushes them as a single
     * {@code submitThumbnailGenerationJob} call after a short debounce. Mirrors
     * {@code LibraryController#queueThumbnailGeneration}.
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

    private void applyCoverFace(long faceId) {
        for (int i = 0; i < allFaces.size(); i++) {
            if (allFaces.get(i)
                        .getId()
                        .equals(faceId)) {
                currentFaceIndex = i;
                break;
            }
        }
        refreshFacePicker();

        runOnDaemonThread("ApplyCoverFace", () -> {
            try {
                personRepository.updateCoverFaceQuery(
                                        currentPerson.getId(), faceId, LocalDateTime.now())
                                .execute();
                currentPerson.setCoverFaceId(faceId);
                log.info("Cover face for person {} set to face {}", currentPerson.getId(), faceId);
            } catch (Exception ex) {
                log.error("Failed to set cover face", ex);
            }
        });
    }

    /**
     * Swaps the placeholder image of any still-*visible* cell for the real thumbnail, once
     * {@code ThumbnailGenerationJob} has generated it. Photo IDs not currently in
     * {@link #visiblePhotoCells} (scrolled out of view since the request was made, or belonging
     * to some other screen's selection) are simply skipped. Mirrors
     * {@code LibraryController#onThumbnailsReady}.
     */
    @EventListener
    public void onThumbnailsReady(ThumbnailsReadyEvent event) {
        ThumbnailUtils.updateThumbnailImage(thumbnailRepository, visiblePhotoCells, event);
    }

    private static boolean hasCachedFile(ThumbnailRecord thumbnail) {
        return thumbnail.getCachePath() != null && new File(thumbnail.getCachePath()).isFile();
    }

    /**
     * Loads a cached thumbnail file, capped to {@link #MAX_THUMBNAIL_DECODE_SIZE}. Mirrors
     * {@code LibraryController#loadThumbnailImage}.
     */
    private Image loadThumbnailImage(ThumbnailRecord thumbnail) {
        File file = new File(thumbnail.getCachePath());
        return new Image(file.toURI()
                             .toString(), MAX_THUMBNAIL_DECODE_SIZE, MAX_THUMBNAIL_DECODE_SIZE, true, true, true);
    }

    private void openSlideshow(List<PhotoRecord> photos, int startIndex) {
        DuplicatesController.openSlideshow(photos, startIndex, photoGridListView.getScene(), fxmlLoader, log);
    }

    private String buildPhotoDetailsText(PhotoRecord photo) {
        return getPhotoDetailsText(photo, size(photo.getFileSize()));
    }

// ── Utilities ─────────────────────────────────────────────────────────────

    private static int compareByDate(PhotoRecord a, PhotoRecord b) {
        if (a.getCaptureDate() == null && b.getCaptureDate() == null) {
            return 0;
        }
        if (a.getCaptureDate() == null) {
            return 1;
        }
        if (b.getCaptureDate() == null) {
            return -1;
        }
        return a.getCaptureDate()
                .compareTo(b.getCaptureDate());
    }
}
