package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.model.AddFilesRequest;
import com.github.curiousoddman.curious_images.model.bundle.AddFilesBundle;
import com.github.curiousoddman.curious_images.ui.util.AlertHelper;
import com.github.curiousoddman.curious_images.ui.util.StageUtils;
import com.github.curiousoddman.curious_images.util.async.jobs.JobDescriptor;
import com.github.curiousoddman.curious_images.util.async.jobs.JobManager;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.stage.DirectoryChooser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Controller for {@code add_files.fxml}.
 * <p>
 * Accepts an {@link AddFilesBundle} pre-populated by either the menu action
 * (empty) or by a drag-and-drop drop event on the library tree (source paths +
 * pre-filled destination root). Validates inputs and delegates to
 * <p>
 * The dialog is opened by {@code LibraryController} via
 * {@code FxmlLoader.load(FxmlView.ADD_FILES, bundle)}, so the bundle is injected
 * before {@link #initialize} is called through the
 * {@link com.github.curiousoddman.curious_images.ui.FxmlLoader} contract.
 */
@Lazy
@Slf4j
@Component
@RequiredArgsConstructor
public class AddFilesController implements Initializable {

    private final JobManager jobManager;

    // ── FXML nodes ────────────────────────────────────────────────────────────

    @FXML
    public ListView<String> sourcePathsList;
    @FXML
    public RadioButton      modeNewRoot;
    @FXML
    public RadioButton      modeCopy;
    @FXML
    public ToggleGroup      modeGroup;
    @FXML
    public HBox             destinationRow;
    @FXML
    public TextField        destinationField;
    @FXML
    public CheckBox         runAiCheckBox;
    @FXML
    public CheckBox         runDedupeCheckBox;

    // ── Bundle (set by FxmlLoader before initialize) ──────────────────────────

    private final ObservableList<String> sourcePaths = FXCollections.observableArrayList();

    // ── Init ──────────────────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        sourcePathsList.setItems(sourcePaths);
        if (resources instanceof AddFilesBundle bundle) {
            if (!bundle.getPrefilledSourcePaths()
                       .isEmpty()) {
                sourcePaths.addAll(bundle.getPrefilledSourcePaths());
            }
            if (bundle.getPrefilledDestinationRoot() != null) {
                destinationField.setText(bundle.getPrefilledDestinationRoot());
                modeCopy.setSelected(true);
                destinationRow.setVisible(true);
                destinationRow.setManaged(true);
            }
        }

        // Show/hide the destination row depending on mode selection
        modeGroup.selectedToggleProperty()
                 .addListener((obs, old, newVal) -> {
                     boolean isCopy = newVal == modeCopy;
                     destinationRow.setVisible(isCopy);
                     destinationRow.setManaged(isCopy);
                 });
    }

    // ── FXML actions ──────────────────────────────────────────────────────────

    @FXML
    public void onBrowseSource() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select folder to import");
        File chosen = chooser.showDialog(sourcePathsList.getScene()
                                                        .getWindow());
        if (chosen != null) {
            String path = chosen.getAbsolutePath();
            if (!sourcePaths.contains(path)) {
                sourcePaths.add(path);
            }
        }
    }

    @FXML
    public void onRemoveSourceEntry() {
        int idx = sourcePathsList.getSelectionModel()
                                 .getSelectedIndex();
        if (idx >= 0) {
            sourcePaths.remove(idx);
        }
    }

    @FXML
    public void onBrowseDestination() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select destination folder");
        if (!destinationField.getText()
                             .isBlank()) {
            File current = new File(destinationField.getText());
            if (current.isDirectory()) {
                chooser.setInitialDirectory(current);
            }
        }
        File chosen = chooser.showDialog(destinationField.getScene()
                                                         .getWindow());
        if (chosen != null) {
            destinationField.setText(chosen.getAbsolutePath());
        }
    }

    @FXML
    public void onStartImport() {
        // ── Validation ────────────────────────────────────────────────────────
        if (sourcePaths.isEmpty()) {
            showError("No source selected", "Please add at least one file or folder to import.");
            return;
        }

        boolean copy = modeCopy.isSelected();
        String dest = destinationField.getText()
                                      .trim();
        if (copy && dest.isBlank()) {
            showError("No destination", "Please choose a destination folder for the copy.");
            return;
        }
        if (copy && !new File(dest).isDirectory()) {
            showError("Invalid destination", "The destination path does not exist or is not a folder:\n" + dest);
            return;
        }

        // ── Delegate ──────────────────────────────────────────────────────────
        AddFilesRequest request = new AddFilesRequest(
                new ArrayList<>(sourcePaths),
                copy,
                copy ? dest : null,
                runAiCheckBox.isSelected(),
                runDedupeCheckBox.isSelected()
        );

        Optional<JobDescriptor> submitted = jobManager.submitAddFilesJob(request);
        boolean                 started   = submitted.isPresent();
        if (!started) {
            showError("Import already running",
                    "A library scan is already in progress.\n"
                            + "Please wait for it to finish before adding more files.");
            return;
        }

        close();
    }

    @FXML
    public void onCancel() {
        close();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void close() {
        StageUtils.closeWindowOf(sourcePathsList);
    }

    private void showError(String title, String message) {
        AlertHelper.showError(sourcePathsList, title, message);
    }
}
