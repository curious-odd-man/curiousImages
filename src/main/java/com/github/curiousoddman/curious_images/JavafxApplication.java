package com.github.curiousoddman.curious_images;

import com.github.curiousoddman.curious_images.app.preloader.MainSceneVisible;
import com.github.curiousoddman.curious_images.config.StageManager;
import com.github.curiousoddman.curious_images.ui.FxmlView;
import com.github.curiousoddman.curious_images.ui.controller.screen.LibraryController;
import javafx.application.Application;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;


public class JavafxApplication extends Application {
    private Stage stage;

    private ConfigurableApplicationContext applicationContext;
    private StageManager                   stageManager;

    @Override
    public void init() {
        applicationContext = new SpringApplicationBuilder(Main.class).run();
    }

    @Override
    public void stop() {
        applicationContext.close();
        stage.close();
    }

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        stage.getIcons()
             .addAll(
                     new Image(getClass().getResourceAsStream("/icons/app-icon-16.png")),
                     new Image(getClass().getResourceAsStream("/icons/app-icon-32.png")),
                     new Image(getClass().getResourceAsStream("/icons/app-icon-64.png")),
                     new Image(getClass().getResourceAsStream("/icons/app-icon-128.png"))
             );
        stageManager = applicationContext.getBean(StageManager.class, primaryStage);
        LibraryController libraryController = stageManager.switchScene(FxmlView.LIBRARY);
        libraryController.setUserPrefs(primaryStage);
        notifyPreloader(new MainSceneVisible());
    }
}
