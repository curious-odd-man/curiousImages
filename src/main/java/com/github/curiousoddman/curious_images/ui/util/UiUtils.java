package com.github.curiousoddman.curious_images.ui.util;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.model.DupResolveStrategy;
import com.github.curiousoddman.curious_images.model.LoadedFxml;
import com.github.curiousoddman.curious_images.model.bundle.SlideshowBundle;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.FxmlView;
import com.github.curiousoddman.curious_images.ui.controller.screen.SlideshowController;
import javafx.animation.ScaleTransition;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Consumer;

/**
 * Small helper shared by modal dialog controllers (Add Files, Rescan Roots, ...) that each used
 * to repeat the same "cast my scene's window to a Stage and close it" snippet in their own
 * {@code close()} method.
 */
@Slf4j
@UtilityClass
public class UiUtils {
    /**
     * Closes the {@link Stage} that owns {@code anyNodeInScene}. Intended for "Cancel"/"OK"
     * buttons on a dialog loaded into its own {@code Stage} (see {@code FxmlLoader} usages that
     * call {@code new Stage()} + {@code stage.setScene(...)}).
     */
    public static void closeWindowOf(Node anyNodeInScene) {
        Stage stage = (Stage) anyNodeInScene.getScene()
                                            .getWindow();
        stage.close();
    }

    public static void openSlideshow(List<PhotoRecord> photos, int startIndex, Scene scene2, FxmlLoader fxmlLoader) {
        try {
            Stage stage = new Stage();
            stage.setTitle("Slideshow");
            stage.initStyle(StageStyle.UNDECORATED);
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.initOwner(scene2.getWindow());

            LoadedFxml<SlideshowController> loaded     = fxmlLoader.load(FxmlView.SLIDESHOW, null);
            SlideshowController             controller = loaded.controller();
            controller.initSlideshow(new SlideshowBundle(photos, startIndex));

            Scene scene = new Scene(loaded.parent());
            stage.setScene(scene);
            stage.setMaximized(true);
            stage.show();
        } catch (Exception ex) {
            log.error("Failed to open slideshow", ex);
        }
    }

    public static void setupDuplicateButtonHover(Button keepAllButton,
                                                 Button deleteAllButton,
                                                 Button keepSelectedButton,
                                                 Button deleteSelectedButton,
                                                 Consumer<DupResolveStrategy> onEnter,
                                                 Runnable onExit) {
        keepAllButton.setOnMouseEntered(e -> onEnter.accept(DupResolveStrategy.KEEP_ALL));
        keepAllButton.setOnMouseExited(e -> onExit.run());
        deleteAllButton.setOnMouseEntered(e -> onEnter.accept(DupResolveStrategy.REMOVE_ALL));
        deleteAllButton.setOnMouseExited(e -> onExit.run());
        keepSelectedButton.setOnMouseEntered(e -> onEnter.accept(DupResolveStrategy.KEEP_CHECKED));
        keepSelectedButton.setOnMouseExited(e -> onExit.run());
        deleteSelectedButton.setOnMouseEntered(e -> onEnter.accept(DupResolveStrategy.REMOVE_CHECKED));
        deleteSelectedButton.setOnMouseExited(e -> onExit.run());
    }

    public static void registerHoverTooltip(Tooltip sharedTooltip, String text, Node... nodes) {
        for (Node node : nodes) {
            node.setOnMouseEntered(e -> {
                sharedTooltip.setText(text);
                Tooltip.install(node, sharedTooltip);
            });

            node.setOnMouseExited(e -> Tooltip.uninstall(node, sharedTooltip));
        }
    }

    public static void fxManage(boolean manage, Node... nodes) {
        for (Node node : nodes) {
            node.setVisible(manage);
            node.setManaged(manage);
        }
    }

    public static void fxManage(Node... parent) {
        fxManage(true, parent);
    }

    public static void fxUnmanage(Node... parent) {
        fxManage(false, parent);
    }

    public static void registerZoomInOnHover(Node... nodes) {
        for (Node node : nodes) {
            ScaleTransition zoomIn = new ScaleTransition(Duration.millis(120), node);
            zoomIn.setToX(1.05);
            zoomIn.setToY(1.05);

            node.setOnMouseEntered(event -> zoomIn.playFromStart());

            ScaleTransition zoomOut = new ScaleTransition(Duration.millis(120), node);
            zoomOut.setToX(1.0);
            zoomOut.setToY(1.0);

            node.setOnMouseExited(event -> zoomOut.playFromStart());
        }
    }
}
