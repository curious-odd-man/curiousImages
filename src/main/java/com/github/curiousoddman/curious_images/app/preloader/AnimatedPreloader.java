package com.github.curiousoddman.curious_images.app.preloader;

import javafx.application.Preloader;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

public class AnimatedPreloader extends Preloader {
    private Stage stage;

    @Override
    public void start(Stage primaryStage) throws Exception {
        this.stage = primaryStage;
        stage.getIcons().addAll(
                new Image(getClass().getResourceAsStream("/icons/app-icon-16.png")),
                new Image(getClass().getResourceAsStream("/icons/app-icon-32.png")),
                new Image(getClass().getResourceAsStream("/icons/app-icon-64.png")),
                new Image(getClass().getResourceAsStream("/icons/app-icon-128.png"))
        );

        Parent root = FXMLLoader.load(
                getClass().getResource("/fxml/preloader.fxml")
        );

        Scene scene = new Scene(root);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    @Override
    public void handleApplicationNotification(PreloaderNotification info) {
        if (info instanceof MainSceneVisible) {
            stage.hide();
        }
    }
}