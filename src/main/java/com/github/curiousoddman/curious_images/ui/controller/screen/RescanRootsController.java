package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.dbobj.tables.records.ImportRootRecord;
import com.github.curiousoddman.curious_images.domain.imports.ImportService;
import com.github.curiousoddman.curious_images.persistence.ImportRootRepository;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Controller for {@code rescan_roots.fxml}.
 * <p>
 * Shows a checkbox list of every known import root so the user can pick which
 * ones to rescan. Clicking "Rescan selected" hands the chosen paths to
 * {@link ImportService#startMultiRootScan(List)}, which runs them sequentially
 * inside a single background job. If a scan is already running the service
 * returns {@code false} and a blocking alert is shown instead.
 */
@Lazy
@Slf4j
@Component
@RequiredArgsConstructor
public class RescanRootsController implements Initializable {

    private final ImportRootRepository importRootRepository;
    private final ImportService        importService;

    @FXML
    public VBox rootsContainer;

    /**
     * One entry per import root in display order.
     */
    private final List<RootEntry> entries = new ArrayList<>();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        for (ImportRootRecord root : importRootRepository.findAll()) {
            CheckBox cb = new CheckBox(root.getPath());
            cb.setSelected(true);           // default: all selected
            cb.setMaxWidth(Double.MAX_VALUE);
            rootsContainer.getChildren()
                          .add(cb);
            entries.add(new RootEntry(root.getPath(), cb));
        }
    }

    @FXML
    public void onSelectAll() {
        entries.forEach(e -> e.checkBox()
                              .setSelected(true));
    }

    @FXML
    public void onSelectNone() {
        entries.forEach(e -> e.checkBox()
                              .setSelected(false));
    }

    @FXML
    public void onRescan() {
        List<String> selected = entries.stream()
                                       .filter(e -> e.checkBox()
                                                     .isSelected())
                                       .map(RootEntry::path)
                                       .toList();
        if (selected.isEmpty()) {
            showInfo("Nothing selected", "Please select at least one root to rescan.");
            return;
        }

        boolean started = importService.startMultiRootScan(selected);
        if (!started) {
            showError("Scan already running",
                    "A library scan is already in progress.\n"
                            + "Please wait for it to finish before starting another.");
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
        Stage stage = (Stage) rootsContainer.getScene()
                                            .getWindow();
        stage.close();
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initOwner(rootsContainer.getScene()
                                      .getWindow());
        alert.showAndWait();
    }

    private void showInfo(String title, String message) {
        Alert alert = new Alert(AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initOwner(rootsContainer.getScene()
                                      .getWindow());
        alert.showAndWait();
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    private record RootEntry(String path, CheckBox checkBox) {}
}
