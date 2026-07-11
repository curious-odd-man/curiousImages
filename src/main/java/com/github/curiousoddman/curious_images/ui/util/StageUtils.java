package com.github.curiousoddman.curious_images.ui.util;

import javafx.scene.Node;
import javafx.stage.Stage;
import lombok.experimental.UtilityClass;

/**
 * Small helper shared by modal dialog controllers (Add Files, Rescan Roots, ...) that each used
 * to repeat the same "cast my scene's window to a Stage and close it" snippet in their own
 * {@code close()} method.
 */
@UtilityClass
public class StageUtils {
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
}
