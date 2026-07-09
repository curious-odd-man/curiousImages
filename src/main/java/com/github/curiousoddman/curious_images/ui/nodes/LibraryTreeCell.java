package com.github.curiousoddman.curious_images.ui.nodes;

import javafx.scene.control.TreeCell;
import org.kordamp.ikonli.javafx.FontIcon;

import static com.sun.javafx.util.Utils.runOnFxThread;

public class LibraryTreeCell extends TreeCell<LibraryTreeNode> {

    @Override
    protected void updateItem(LibraryTreeNode node, boolean empty) {
        runOnFxThread(() -> {
            super.updateItem(node, empty);
            if (empty || node == null) {
                setText(null);
                setGraphic(null);
            } else {
                setText(node.toString());
                FontIcon icon = new FontIcon(node.icon());
                icon.setIconSize(16);
                setGraphic(icon);
            }
        });
    }
}