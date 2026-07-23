package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.config.AiConfig;
import com.github.curiousoddman.curious_images.config.AiSettingsService;
import com.github.curiousoddman.curious_images.config.RuntimeSettingsBootstrap;
import com.github.curiousoddman.curious_images.ui.util.AlertHelper;
import com.github.curiousoddman.curious_images.ui.util.UiUtils;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * Controller for {@code settings.fxml}.
 * <p>
 * Two groups of controls, visually separated:
 * <ul>
 *     <li><b>Live</b> settings (performance + album tuning) apply immediately via
 *     {@link AiSettingsService} as soon as the user changes them - no "Save" button needed for
 *     these, though closing via "Done" is the expected flow.</li>
 *     <li><b>Restart required</b> settings (storage paths) are staged in memory and only written
 *     to {@link RuntimeSettingsBootstrap}'s file when the user clicks "Save &amp; close", with a
 *     clear note that they take effect on next launch.</li>
 * </ul>
 */
@Lazy
@Slf4j
@Component
@RequiredArgsConstructor
public class SettingsController implements Initializable {

    private final AiSettingsService aiSettingsService;

    @Value("${app.ai.index-root}")
    private String currentIndexRoot;
    @Value("${app.thumbnail-cache.dir}")
    private String currentThumbnailCacheDir;

    // ── Performance (live) ──────────────────────────────────────────────────────
    @FXML
    public ChoiceBox<AiConfig.ExecutionProvider> executionProviderChoice;
    @FXML
    public Spinner<Integer>                      intraOpThreadsSpinner;
    @FXML
    public Spinner<Integer>                      batchSizeSpinner;
    @FXML
    public Spinner<Integer>                      dedupeThreadCountSpinner;
    @FXML
    public CheckBox                              faceOnlyCheckBox;

    // ── Album tuning (live) ──────────────────────────────────────────────────────
    @FXML
    public Spinner<Integer> eventGapHoursSpinner;
    @FXML
    public Spinner<Integer> minEventSizeSpinner;
    @FXML
    public Spinner<Integer> minLocationSizeSpinner;
    @FXML
    public Spinner<Integer> minClusterSizeSpinner;
    @FXML
    public Spinner<Double>  minClusterSimilaritySpinner;

    // ── Storage (restart required) ───────────────────────────────────────────────
    @FXML
    public TextField modelDirField;
    @FXML
    public TextField indexRootField;
    @FXML
    public TextField thumbnailCacheDirField;
    @FXML
    public Label      restartNoticeLabel;

    @FXML
    public VBox root;

    private boolean initializing = true;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        AiConfig config = aiSettingsService.config();

        // Performance
        executionProviderChoice.getItems()
                               .setAll(AiConfig.ExecutionProvider.values());
        executionProviderChoice.setValue(config.getExecutionProvider());
        executionProviderChoice.setOnAction(e -> {
            if (!initializing) {
                aiSettingsService.setExecutionProvider(executionProviderChoice.getValue());
            }
        });

        intraOpThreadsSpinner.setValueFactory(intFactory(1, 64, config.getIntraOpThreads()));
        onIntChange(intraOpThreadsSpinner, aiSettingsService::setIntraOpThreads);

        batchSizeSpinner.setValueFactory(intFactory(1, 128, config.getBatchSize()));
        onIntChange(batchSizeSpinner, aiSettingsService::setBatchSize);

        dedupeThreadCountSpinner.setValueFactory(intFactory(1, 32, config.getDuplicateDetectionThreadCount()));
        onIntChange(dedupeThreadCountSpinner, aiSettingsService::setDuplicateDetectionThreadCount);

        faceOnlyCheckBox.setSelected(config.isFaceOnly());
        faceOnlyCheckBox.selectedProperty()
                        .addListener((obs, was, isNow) -> {
                            if (!initializing) {
                                aiSettingsService.setFaceOnly(isNow);
                            }
                        });

        // Album tuning
        eventGapHoursSpinner.setValueFactory(intFactory(1, 168, config.getEventGapHours()));
        onIntChange(eventGapHoursSpinner, aiSettingsService::setEventGapHours);

        minEventSizeSpinner.setValueFactory(intFactory(1, 200, config.getMinEventSize()));
        onIntChange(minEventSizeSpinner, aiSettingsService::setMinEventSize);

        minLocationSizeSpinner.setValueFactory(intFactory(1, 200, config.getMinLocationSize()));
        onIntChange(minLocationSizeSpinner, aiSettingsService::setMinLocationSize);

        minClusterSizeSpinner.setValueFactory(intFactory(1, 500, config.getMinClusterSize()));
        onIntChange(minClusterSizeSpinner, aiSettingsService::setMinClusterSize);

        minClusterSimilaritySpinner.setValueFactory(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(0.0, 1.0, config.getMinClusterSimilarity(), 0.05));
        minClusterSimilaritySpinner.valueProperty()
                                   .addListener((obs, was, isNow) -> {
                                       if (!initializing) {
                                           aiSettingsService.setMinClusterSimilarity(isNow.floatValue());
                                       }
                                   });

        // Storage (restart required)
        modelDirField.setText(config.getModelDir()
                                    .toString());
        indexRootField.setText(currentIndexRoot);
        thumbnailCacheDirField.setText(currentThumbnailCacheDir);

        initializing = false;
    }

    @FXML
    public void onSaveStorageSettings() {
        RuntimeSettingsBootstrap.save(Map.of(
                RuntimeSettingsBootstrap.KEY_MODEL_DIR, modelDirField.getText(),
                RuntimeSettingsBootstrap.KEY_INDEX_ROOT, indexRootField.getText(),
                RuntimeSettingsBootstrap.KEY_THUMBNAIL_CACHE_DIR, thumbnailCacheDirField.getText()
        ));
        AlertHelper.showInfo(root, "Saved",
                "Storage paths saved. Restart curiousImages for the new locations to take effect.");
    }

    @FXML
    public void onClose() {
        UiUtils.closeWindowOf(root);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private SpinnerValueFactory<Integer> intFactory(int min, int max, int initial) {
        return new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max, initial);
    }

    private void onIntChange(Spinner<Integer> spinner, java.util.function.IntConsumer apply) {
        spinner.valueProperty()
               .addListener((obs, was, isNow) -> {
                   if (!initializing && isNow != null) {
                       apply.accept(isNow);
                   }
               });
    }
}
