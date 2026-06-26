package com.github.curiousoddman.curious_images.app.preloader;

import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.RotateTransition;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

public class PreloaderController implements Initializable {

    @FXML
    public  AnchorPane rootPane;
    @FXML
    private Circle     loaderCircle;
    @FXML
    private Label      loadingText;
    @FXML
    private Pane       carouselPane;

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

        Color[] colors = {
                Color.CORNFLOWERBLUE,
                Color.SALMON,
                Color.GOLD,
                Color.MEDIUMSEAGREEN,
                Color.PLUM,
                Color.ORANGE,
                Color.TEAL
        };

        for (int i = 0; i < colors.length; i++) {

            Album album = new Album(colors[i]);
            album.x = i * spacing;

            albums.add(album);

            carouselPane.getChildren()
                        .add(album.node);
        }

        startAnimation();

    }

    private void startAnimation() {

        AnimationTimer timer = new AnimationTimer() {

            private long previous;

            @Override
            public void handle(long now) {

                if (previous == 0) {
                    previous = now;
                    return;
                }

                double dt = (now - previous) / 1_000_000_000.0;
                previous = now;

                update(dt);
            }
        };

        timer.start();
    }

    private void update(double dt) {

        double totalWidth = albums.size() * spacing;

        for (Album album : albums) {

            album.x -= speed * dt;

            if (album.x < -spacing) {
                album.x += totalWidth;
            }

            layout(album);
        }
    }

    private void layout(Album album) {

        double x = album.x;

        album.node.setLayoutX(x);
        album.node.setLayoutY(centerY);

        double distance = Math.abs(x - centerX);

        double normalized = Math.min(distance / 300.0, 1.0);

        double scale = 0.8 + Math.cos(normalized * Math.PI / 2) * 0.5;

        album.node.setScaleX(scale);
        album.node.setScaleY(scale);

        album.node.setOpacity(0.4 + (scale - 0.8) / 0.5 * 0.6);

        album.node.setTranslateY(normalized * 20);

        album.node.setRotate((x - centerX) * 0.05);

        album.node.toFront();
    }

    private static class Album {
        StackPane node;
        double    x;

        Album(Color color) {

            Rectangle cover = new Rectangle(120, 120);
            cover.setArcWidth(20);
            cover.setArcHeight(20);
            cover.setFill(color);

            Rectangle border = new Rectangle(120, 120);
            border.setArcWidth(20);
            border.setArcHeight(20);
            border.setFill(Color.TRANSPARENT);
            border.setStroke(Color.WHITE);

            node = new StackPane(cover, border);
        }
    }

    private final List<Album> albums = new ArrayList<>();

    private final double spacing = 150;
    private final double speed   = 90;

    private final double centerX = 400;
    private final double centerY = 120;
}