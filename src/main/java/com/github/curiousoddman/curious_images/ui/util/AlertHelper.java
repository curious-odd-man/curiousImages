package com.github.curiousoddman.curious_images.ui.util;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;
import lombok.experimental.UtilityClass;

/**
 * Single place that knows how to build/show the app's {@link Alert} dialogs.
 * <p>
 * Every controller used to construct its own {@code Alert} (error/info popup, or a
 * confirmation with OK/CANCEL) with slightly different boilerplate for
 * {@code setHeaderText}/{@code initOwner}/result-checking. That duplication is what the
 * {@code // FIXME: centralize Alert creation} comments (see {@code RescanRootsController},
 * {@code AddFilesController}) were pointing at — this class is that centralization point.
 */
@UtilityClass
public final class AlertHelper {

    /**
     * Blocking error popup with an OK button.
     */
    public static void showError(Node ownerNode, String title, String message) {
        show(ownerNode, Alert.AlertType.ERROR, title, null, message);
    }

    /**
     * Blocking informational popup with an OK button.
     */
    public static void showInfo(Node ownerNode, String title, String message) {
        show(ownerNode, Alert.AlertType.INFORMATION, title, null, message);
    }

    /**
     * Blocking warning popup with an OK button.
     */
    public static void showWarning(Node ownerNode, String title, String message) {
        show(ownerNode, Alert.AlertType.WARNING, title, null, message);
    }

    /**
     * Blocking OK/CANCEL confirmation. Returns {@code true} only if the user explicitly picked
     * OK (closing the dialog any other way, e.g. Escape/the window close button, counts as "no").
     */
    public static boolean confirm(Node ownerNode, String headerText, String contentText) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, contentText, ButtonType.OK, ButtonType.CANCEL);
        alert.setHeaderText(headerText);
        applyOwner(alert, ownerNode);
        return alert.showAndWait()
                    .filter(bt -> bt == ButtonType.OK)
                    .isPresent();
    }

    private static void show(Node ownerNode, Alert.AlertType type, String title, String headerText, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(headerText);
        alert.setContentText(message);
        applyOwner(alert, ownerNode);
        alert.showAndWait();
    }

    private static void applyOwner(Alert alert, Node ownerNode) {
        if (ownerNode != null && ownerNode.getScene() != null) {
            Window owner = ownerNode.getScene()
                                    .getWindow();
            if (owner != null) {
                alert.initOwner(owner);
            }
        }
    }
}
