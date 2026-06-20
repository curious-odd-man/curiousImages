package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.config.FxmlLoader;
import com.github.curiousoddman.curious_images.config.FxmlView;
import com.github.curiousoddman.curious_images.domain.user.prefs.UserPreferencesService;
import com.github.curiousoddman.curious_images.event.BackgroundProcessEvent;
import com.github.curiousoddman.curious_images.event.InterruptBackgroundProcessEvent;
import com.github.curiousoddman.curious_images.model.bundle.RescanBundle;
import com.github.curiousoddman.curious_images.util.async.DelayedAction;
import javafx.beans.InvalidationListener;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.github.curiousoddman.curious_images.domain.imports.ImportService.IMPORT_SCAN;
import static com.sun.javafx.util.Utils.runOnFxThread;

@Lazy
@Slf4j
@Component
@RequiredArgsConstructor
public class LibraryController implements Initializable {
    private final ApplicationEventPublisher eventPublisher;
    private final FxmlLoader fxmlLoader;
    @FXML
    public SplitPane librarySplitPane;

    // Import status bar (§11) — the only new UI in this phase.
    @FXML
    public Label importProgressLabel;
    @FXML
    public Label importCurrentFileLabel;
    @FXML
    public Label importElapsedLabel;
    @FXML
    public Button importCancelButton;

    private final UserPreferencesService userPreferencesService;

    @Override
    @SneakyThrows
    public void initialize(URL location, ResourceBundle resources) {
        onLibraryDataUpdated();
    }

    public void setUserPrefs(Stage primaryStage) {
        userPreferencesService.restoreWindowState(primaryStage);
        DelayedAction delayedSaveWindowSize = new DelayedAction(500, TimeUnit.MILLISECONDS);
        InvalidationListener invalidationListener = o ->
                delayedSaveWindowSize.reSchedule(() ->
                        userPreferencesService.saveWindowState(primaryStage));

        primaryStage.widthProperty().addListener(invalidationListener);
        primaryStage.heightProperty().addListener(invalidationListener);
        primaryStage.xProperty().addListener(invalidationListener);
        primaryStage.yProperty().addListener(invalidationListener);
        primaryStage.maximizedProperty().addListener(invalidationListener);

        librarySplitPane.setDividerPositions(userPreferencesService.getDividerPositions());
        DelayedAction delayedSaveDividerPosition = new DelayedAction(500, TimeUnit.MILLISECONDS);

        InvalidationListener splitPanePositionListener = o ->
                delayedSaveDividerPosition.reSchedule(() ->
                        userPreferencesService.saveSplitPositions(librarySplitPane.getDividerPositions()));

        librarySplitPane.getDividers().getFirst().positionProperty().addListener(splitPanePositionListener);
        librarySplitPane.getDividers().getLast().positionProperty().addListener(splitPanePositionListener);
    }

    @EventListener
    public void onBackgroundProcessEvent(BackgroundProcessEvent event) {
        if (!event.getProcessName().equals(IMPORT_SCAN)) {
            return;
        }
        runOnFxThread(() -> {
            importProgressLabel.setText(event.getMaxProgress() > 0
                    ? event.getProgress() + " / " + event.getMaxProgress()
                    : event.getDescription());
            importCurrentFileLabel.setText(event.getCurrentItem() == null ? "" : event.getCurrentItem());
            long elapsedMs = System.currentTimeMillis() - event.getTimestamp();
            importElapsedLabel.setText(Duration.ofMillis(elapsedMs).toString());
            importCancelButton.setVisible(!event.getEventType().isTerminal());
        });
    }

    @FXML
    public void onCancelImport(ActionEvent actionEvent) {
        eventPublisher.publishEvent(new InterruptBackgroundProcessEvent(this));
    }

    @SneakyThrows
    private void onLibraryDataUpdated() {
        log.info("Update UI from library in separate thread");
        Thread t = new Thread(() -> {

        });
        t.start();
    }

    @FXML
    @SneakyThrows
    public void onRescanMenuClicked(ActionEvent actionEvent) {
        Stage stage = new Stage();
        Parent root = fxmlLoader.load(FxmlView.RESCAN_MODAL, new RescanBundle("D:\\My Pictures")).parent();
        stage.setScene(new Scene(root));
        stage.setTitle("Rescan library");
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initOwner(importCancelButton.getScene().getWindow());
        stage.showAndWait();
    }
}
