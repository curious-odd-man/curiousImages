package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PersonRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.domain.ai.PersonCorrectionService;
import com.github.curiousoddman.curious_images.event.model.ThumbnailsReadyEvent;
import com.github.curiousoddman.curious_images.event.model.TreeViewUpdateEvent;
import com.github.curiousoddman.curious_images.event.payload.TreeViewUpdatePayload;
import com.github.curiousoddman.curious_images.model.LoadedFxml;
import com.github.curiousoddman.curious_images.model.bundle.PhotoCellResources;
import com.github.curiousoddman.curious_images.persistence.FaceRepository;
import com.github.curiousoddman.curious_images.persistence.PersonRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoRepository;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.FxmlView;
import com.github.curiousoddman.curious_images.ui.controller.custom.PhotoGridController;
import com.github.curiousoddman.curious_images.ui.controller.services.PhotoGridManager;
import com.github.curiousoddman.curious_images.ui.controller.services.ThumbnailReadyEventListener;
import com.github.curiousoddman.curious_images.ui.styles.CssClasses;
import com.github.curiousoddman.curious_images.ui.util.AlertHelper;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.ResourceBundle;

import static com.github.curiousoddman.curious_images.ui.controller.screen.FacePickerCellController.loadImage;
import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static com.sun.javafx.util.Utils.runOnFxThread;

@Lazy
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonDetailController implements Initializable, ThumbnailReadyEventListener {

    private final PersonRepository          personRepository;
    private final FaceRepository            faceRepository;
    private final PhotoRepository           photoRepository;
    private final FxmlLoader                fxmlLoader;
    private final PersonCorrectionService   personCorrectionService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final PhotoGridManager          photoGridManager;

    @FXML
    public TitledPane        profilePane;
    @FXML
    public ImageView         faceImageView;
    @FXML
    public ProgressIndicator faceLoadIndicator;
    @FXML
    public Button            browseFacesButton;
    @FXML
    public Button            mergeIntoButton;
    @FXML
    public TextField         nameField;
    @FXML
    public TextField         dobField;
    @FXML
    public TextArea          notesArea;
    @FXML
    public Label             editHintLabel;
    @FXML
    public BorderPane        gridBorderPane;
    @FXML
    public TreeView<String>  ageAlbumTree;

    // ── State ─────────────────────────────────────────────────────────────────

    private Image               noImageAvailable;
    private PersonRecord        currentPerson;
    private PhotoGridController photoGridController;

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

    // ── Initialisation ────────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        noImageAvailable = new Image(
                Objects.requireNonNull(getClass().getResourceAsStream("/img/noimage.png")));

        wireEditableControl(nameField, this::commitName);
        wireEditableControl(notesArea, this::commitNotes);
        wireEditableControl(dobField, this::commitDob);

        ageAlbumTree.getSelectionModel()
                    .selectedItemProperty()
                    .addListener((obs, oldItem, newItem) -> onAgeAlbumSelected(newItem));

        LoadedFxml<PhotoGridController> loaded = fxmlLoader.load(FxmlView.PHOTO_GRID, resources);
        photoGridController = loaded.controller();
        gridBorderPane.setCenter(loaded.parent());
    }

    // ── Public API called by LibraryController ────────────────────────────────

    /**
     * Load everything for the given person.  Safe to call from any thread
     * (kicks off background work internally).
     */
    public void loadPerson(long personId) {
        long myGeneration = photoGridController.initiateChange();
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
                if (myGeneration != photoGridController.currentChange()) {
                    return; // a newer loadPerson() call has since superseded this one
                }
                currentPerson = person;
                allFaces = faces;
                currentFaceIndex = startIndex;
                photosByYear = byYear;

                mergeIntoButton.setDisable(false);
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
     * Makes any {@link TextInputControl} (covers both {@link TextField} and {@link TextArea} —
     * {@code nameField}/{@code dobField} are the former, {@code notesArea} the latter) behave as
     * a read-only label until double-clicked, then commits on focus-loss, cancels on Escape.
     * {@link TextField} additionally commits on Enter (a {@link TextArea} treats Enter as a
     * newline instead, so that key is left alone there).
     */
    private void wireEditableControl(TextInputControl control, Runnable onCommit) {
        control.getStyleClass()
               .add(CssClasses.EDITABLE_FIELD);
        control.setEditable(false);

        control.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                activateControl(control);
            }
        });

        String[] savedValue = {control.getText()};

        control.focusedProperty()
               .addListener((obs, wasFocused, isFocused) -> {
                   if (wasFocused && !isFocused) {
                       // commit on focus-loss (e.g. Tab-out)
                       deactivateControl(control);
                       onCommit.run();
                   }
                   if (isFocused) {
                       savedValue[0] = control.getText();
                   }
               });

        control.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && control instanceof TextField) {
                deactivateControl(control);
                onCommit.run();
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                control.setText(savedValue[0]);
                deactivateControl(control);
                e.consume();
            }
        });
    }

    private void activateControl(TextInputControl control) {
        control.setEditable(true);
        control.getStyleClass()
               .add(CssClasses.EDITABLE_FIELD_ACTIVE);
        control.requestFocus();
        if (control instanceof TextField field) {
            field.selectAll();
        }
    }

    private void deactivateControl(TextInputControl control) {
        control.setEditable(false);
        control.getStyleClass()
               .remove(CssClasses.EDITABLE_FIELD_ACTIVE);
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
                applicationEventPublisher.publishEvent(new TreeViewUpdateEvent(this, new TreeViewUpdatePayload.PersonRename(currentPerson.getId(), value)));
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

        boolean confirmed = AlertHelper.confirm(nameField, "Merge people",
                "Merge \"" + nameField.getText() + "\" into \"" + chosenLabel.get() + "\"? "
                        + "All of this person's face groups will become part of the target. "
                        + "This can't be undone from the UI.");
        if (!confirmed) {
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

        // FIXME: This is strange workaround. there is 1 instance of manager and 2 instances of controllers.
        // One manager cannot manage 2 controllers simultaneously. this must be unentangled.
        photoGridController.populatePhotoGrid(photoGridManager.createData(toShow));
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

    @Override
    public void onThumbnailReady(ThumbnailsReadyEvent event) {
        if (photoGridController != null) {
            photoGridController.onThumbnailReady(event);
        }
    }
}
