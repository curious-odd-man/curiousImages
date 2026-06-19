package com.github.curiousoddman.curious_images.config;

import com.github.curiousoddman.curious_images.event.UserShutdownApplication;
import com.github.curiousoddman.curious_images.model.LoadedFxml;
import javafx.scene.Scene;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

@Slf4j
public class StageManager {
    private final FxmlLoader fxmlLoader;
    private final Stage primaryStage;
    private final String applicationTitle;

    public StageManager(FxmlLoader fxmlLoader,
                        Stage primaryStage,
                        String applicationTitle,
                        ApplicationEventPublisher eventPublisher) {
        this.fxmlLoader = fxmlLoader;
        this.primaryStage = primaryStage;
        this.applicationTitle = applicationTitle;

        primaryStage.setOnCloseRequest(event -> {
            log.info("User requested application shutdown");
            eventPublisher.publishEvent(new UserShutdownApplication(this));
        });
    }

    public <T> T switchScene(FxmlView<T> view) {
        primaryStage.setTitle(applicationTitle);
        primaryStage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);

        LoadedFxml<T> loaded = loadRootNode(view);
        Scene scene = new Scene(loaded.parent());
        scene.getStylesheets().add("styles/global.css");

        primaryStage.setScene(scene);
        primaryStage.show();
        return loaded.controller();
    }

    private <T> LoadedFxml<T> loadRootNode(FxmlView<T> fxmlPath) {
        return fxmlLoader.load(fxmlPath, null);
    }
}
