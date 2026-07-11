package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.dbobj.tables.records.ImportRootRecord;
import com.github.curiousoddman.curious_images.domain.imports.ImportJob;
import com.github.curiousoddman.curious_images.persistence.ImportRootRepository;
import com.github.curiousoddman.curious_images.ui.util.AlertHelper;
import com.github.curiousoddman.curious_images.ui.util.StageUtils;
import com.github.curiousoddman.curious_images.util.async.jobs.JobDescriptor;
import com.github.curiousoddman.curious_images.util.async.jobs.JobManager;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.VBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Controller for {@code rescan_roots.fxml}.
 * <p>
 * Shows a checkbox list of every known import root so the user can pick which
 * ones to rescan. Clicking "Rescan selected" hands the chosen paths to
 * {@link ImportJob#startMultiRootScan(List)}, which runs them sequentially
 * inside a single background job. If a scan is already running the service
 * returns {@code false} and a blocking alert is shown instead.
 */
@Lazy
@Slf4j
@Component
@RequiredArgsConstructor
public class RescanRootsController implements Initializable {

    private final ImportRootRepository importRootRepository;
    private final JobManager           jobManager;

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

        Optional<JobDescriptor> submit = jobManager.submitImportJob(selected);

        boolean started = submit.isPresent();
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
        StageUtils.closeWindowOf(rootsContainer);
    }

    private void showError(String title, String message) {
        AlertHelper.showError(rootsContainer, title, message);
    }

    private void showInfo(String title, String message) {
        AlertHelper.showInfo(rootsContainer, title, message);
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    private record RootEntry(String path, CheckBox checkBox) {}
}
