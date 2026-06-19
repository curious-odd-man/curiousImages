package com.github.curiousoddman.curious_images.test;

import javafx.application.Application;
import javafx.stage.Stage;
import lombok.Getter;
import lombok.Setter;


@Getter
@Setter
public class TestApp extends Application {
    private Stage stage;
    private Runnable afterStartup = () -> {};


    public void launch() {
        Application.launch(TestApp.class);
    }

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        afterStartup.run();
    }

    @Override
    public void stop() {
        stage.close();
    }
}
