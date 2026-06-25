package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PersonRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ThumbnailRecord;
import com.github.curiousoddman.curious_images.persistence.FaceRepository;
import com.github.curiousoddman.curious_images.persistence.PersonRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoRepository;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
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
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.nio.file.Path;
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

import static com.github.curiousoddman.curious_images.ui.controller.screen.DuplicatesController.getPhotoDetailsText;
import static com.github.curiousoddman.curious_images.ui.controller.screen.LibraryController.humanReadableSize;
import static com.github.curiousoddman.curious_images.ui.controller.screen.SlideshowController.getImage;
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
public class PersonDetailController implements Initializable {

    // ── Injected services ─────────────────────────────────────────────────────

    private final PersonRepository    personRepository;
    private final FaceRepository      faceRepository;
    private final PhotoRepository     photoRepository;
    private final ThumbnailRepository thumbnailRepository;

    // ── FXML nodes ────────────────────────────────────────────────────────────

    @FXML
    public TitledPane                  profilePane;
    @FXML
    public ImageView                   faceImageView;
    @FXML
    public ProgressIndicator           faceLoadIndicator;
    @FXML
    public javafx.scene.control.Button prevFaceButton;
    @FXML
    public javafx.scene.control.Button nextFaceButton;
    @FXML
    public Label                       faceIndexLabel;
    @FXML
    public TextField                   nameField;
    @FXML
    public TextField                   dobField;
    @FXML
    public TextArea                    notesArea;
    @FXML
    public Label                       editHintLabel;
    @FXML
    public Slider                      thumbnailSizeSlider;
    @FXML
    public Label                       photoCountLabel;
    @FXML
    public TreeView<String>            ageAlbumTree;
    @FXML
    public FlowPane                    photoGridPane;

    // ── State ─────────────────────────────────────────────────────────────────

    private Image        noImageAvailable;
    private PersonRecord currentPerson;

    /**
     * All face records for the current person, in stable order.
     */
    private List<FaceRecord> allFaces = List.of();

    /**
     * Index into {@link #allFaces} for the face currently shown in the picker.
     * The face at this index is NOT necessarily the saved cover face until the
     * user presses "Set as cover".
     */
    private int currentFaceIndex = 0;

    /**
     * Photos grouped by year (calendar year of capture_date), ordered by date taken.
     * Key {@code null} = undated.
     */
    private Map<Integer, List<PhotoRecord>> photosByYear = new LinkedHashMap<>();

    /**
     * Cache of thumbnails keyed by photo id, populated per batch.
     */
    private Map<Long, ThumbnailRecord> thumbCache = new LinkedHashMap<>();

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
    }

    // ── Public API called by LibraryController ────────────────────────────────

    /**
     * Load everything for the given person.  Safe to call from any thread
     * (kicks off background work internally).
     */
    public void loadPerson(long personId) {
        Thread t = new Thread(() -> {
            Optional<PersonRecord> opt = personRepository.findById(personId);
            if (opt.isEmpty()) {
                log.warn("Person {} not found", personId);
                return;
            }
            PersonRecord person = opt.get();

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

            Map<Long, ThumbnailRecord>      thumbs = thumbnailRepository.findByPhotoIds(photoIds);
            Map<Integer, List<PhotoRecord>> byYear = groupByYear(photos);

            runOnFxThread(() -> {
                currentPerson = person;
                allFaces = faces;
                currentFaceIndex = startIndex;
                photosByYear = byYear;
                thumbCache = thumbs;

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
        t.setDaemon(true);
        t.start();
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
        Thread t = new Thread(() -> {
            try {
                personRepository.updateNameQuery(currentPerson.getId(), value, LocalDateTime.now())
                                .execute();
                currentPerson.setName(value);
            } catch (Exception ex) {
                log.error("Failed to save name for person {}", currentPerson.getId(), ex);
            }
        });
        t.setDaemon(true);
        t.start();
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
        Thread t = new Thread(() -> {
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
        t.setDaemon(true);
        t.start();
    }

    private void commitNotes() {
        if (currentPerson == null) {
            return;
        }
        String value = notesArea.getText();
        Thread t = new Thread(() -> {
            try {
                personRepository.updateNotes(currentPerson.getId(), value, LocalDateTime.now());
                currentPerson.setNotes(value);
            } catch (Exception ex) {
                log.error("Failed to save notes for person {}", currentPerson.getId(), ex);
            }
        });
        t.setDaemon(true);
        t.start();
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
        faceIndexLabel.setText((total == 0) ? "—" : (currentFaceIndex + 1) + " / " + total);
        prevFaceButton.setDisable(total <= 1);
        nextFaceButton.setDisable(total <= 1);

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

        Thread t = new Thread(() -> {
            Image img = Optional.ofNullable(face.getThumbnailAbsolutePath())
                                .map(Path::of)
                                .map(Path::toUri)
                                .map(uri -> new Image(uri.toString(), 0, 0, true, true, true))
                                .orElse(noImageAvailable);

            runOnFxThread(() -> {
                faceImageView.setImage(img);
                faceLoadIndicator.setVisible(false);
            });
        });
        t.setDaemon(true);
        t.start();
    }

    @FXML
    public void onPrevFace() {
        if (allFaces.isEmpty()) {
            return;
        }
        currentFaceIndex = (currentFaceIndex - 1 + allFaces.size()) % allFaces.size();
        refreshFacePicker();
    }

    @FXML
    public void onNextFace() {
        if (allFaces.isEmpty()) {
            return;
        }
        currentFaceIndex = (currentFaceIndex + 1) % allFaces.size();
        refreshFacePicker();
    }

    @FXML
    public void onSetCoverFace() {
        if (currentPerson == null || allFaces.isEmpty()) {
            return;
        }
        long faceId = allFaces.get(currentFaceIndex)
                              .getId();
        Thread t = new Thread(() -> {
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
        t.setDaemon(true);
        t.start();
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
        root.setExpanded(true);

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

    // ── Photo grid ────────────────────────────────────────────────────────────

    private void populatePhotoGrid(List<PhotoRecord> photos) {
        photoGridPane.getChildren()
                     .setAll(
                             photos.stream()
                                   .map(p -> createPhotoCell(p, thumbCache.get(p.getId())))
                                   .toList());
        photoCountLabel.setText(photos.size() + " photo" + (photos.size() == 1 ? "" : "s"));
    }

    private Node createPhotoCell(PhotoRecord photo, ThumbnailRecord thumbnail) {
        ImageView imageView = new ImageView(getImage(thumbnail, noImageAvailable));
        imageView.setPreserveRatio(true);
        imageView.fitWidthProperty()
                 .bind(thumbnailSizeSlider.valueProperty());
        imageView.fitHeightProperty()
                 .bind(thumbnailSizeSlider.valueProperty());

        Label nameLabel = new Label(photo.getFilename());
        nameLabel.setWrapText(true);
        nameLabel.setAlignment(Pos.CENTER);
        nameLabel.setMaxWidth(160.0);

        VBox cell = new VBox(4.0, imageView, nameLabel);
        cell.setAlignment(Pos.TOP_CENTER);
        cell.setPadding(new Insets(6.0));

        javafx.scene.control.Tooltip tooltip =
                new javafx.scene.control.Tooltip(buildPhotoDetailsText(photo));
        tooltip.setShowDelay(javafx.util.Duration.millis(500));
        tooltip.setFont(LibraryController.CONSOLAS);
        javafx.scene.control.Tooltip.install(cell, tooltip);

        cell.setOnMouseClicked(e -> {
            if (e.getClickCount() == 1) {
                List<PhotoRecord> currentPhotos = photoGridPane.getChildren()
                                                               .stream()
                                                               .map(node -> (PhotoRecord) node.getUserData())
                                                               .filter(Objects::nonNull)
                                                               .toList();
                int idx = currentPhotos.indexOf(photo);
                // Slideshow opening — delegate to LibraryController helper if exposed,
                // or replicate the same Stage-based pattern used there.
                // (Wire in your FxmlLoader here if you extract openSlideshow to a shared util.)
            }
        });
        cell.setUserData(photo);
        return cell;
    }

    private String buildPhotoDetailsText(PhotoRecord photo) {
        return getPhotoDetailsText(photo, humanReadableSize(photo.getFileSize()));
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
