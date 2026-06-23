package com.github.curiousoddman.curious_images.app.preloader;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.RotateTransition;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.layout.AnchorPane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.CubicCurveTo;
import javafx.scene.shape.Path;
import javafx.util.Duration;

import java.net.URL;
import java.util.ResourceBundle;
import java.util.concurrent.ThreadLocalRandom;

public class PreloaderController implements Initializable {

    @FXML
    public  CubicCurveTo wave1Curve;
    @FXML
    public  CubicCurveTo wave2Curve;
    @FXML
    public  CubicCurveTo wave3Curve;
    @FXML
    public  Path         wave1;
    @FXML
    public  Path         wave2;
    @FXML
    public  Path         wave3;
    @FXML
    public  AnchorPane   rootPane;
    @FXML
    private Circle       loaderCircle;
    @FXML
    private Label        loadingText;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        RotateTransition rotate = new RotateTransition(Duration.seconds(1.5), loaderCircle);
        rotate.setByAngle(360);
        rotate.setCycleCount(Animation.INDEFINITE);
        rotate.setInterpolator(Interpolator.LINEAR);

        FadeTransition fade = new FadeTransition(Duration.seconds(1.2), loadingText);
        fade.setFromValue(1.0);
        fade.setToValue(0.3);
        fade.setCycleCount(Animation.INDEFINITE);
        fade.setAutoReverse(true);

        createWaveAndAnimate(wave1Curve, 200, 0.8);
        createWaveAndAnimate(wave2Curve, 200, 1);
        createWaveAndAnimate(wave3Curve, 200, 1.2);

        rotate.play();
        fade.play();
    }

    private void createWaveAndAnimate(CubicCurveTo cubicCurveTo, double amplitude, double speed) {
        var               baseY             = cubicCurveTo.getY();
        ThreadLocalRandom threadLocalRandom = ThreadLocalRandom.current();
        int               sign              = threadLocalRandom.nextInt() % 2 == 0 ? 1 : -1;
        Timeline timeline = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(cubicCurveTo.controlY1Property(), baseY + amplitude * sign * threadLocalRandom.nextDouble(0.2, 1)),
                        new KeyValue(cubicCurveTo.controlY2Property(), baseY - amplitude * sign * threadLocalRandom.nextDouble(0.2, 1))
                ),
                new KeyFrame(Duration.seconds(4 / speed),
                        new KeyValue(cubicCurveTo.controlY1Property(), baseY - amplitude * sign * threadLocalRandom.nextDouble(0.2, 1)),
                        new KeyValue(cubicCurveTo.controlY2Property(), baseY + amplitude * sign * threadLocalRandom.nextDouble(0.2, 1))
                )
        );

        timeline.setAutoReverse(true);
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
    }
}