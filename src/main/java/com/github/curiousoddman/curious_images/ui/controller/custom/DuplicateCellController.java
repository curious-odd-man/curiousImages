package com.github.curiousoddman.curious_images.ui.controller.custom;

import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Controller for {@code duplicate_cell.fxml} — one photo within a duplicate group on the
 * Duplicates tab (thumbnail + inline metadata + "Keep" checkbox).
 * <p>
 * <b>Scope:</b> {@code prototype}, not the app's usual singleton {@code @Component} — a fresh
 * instance is created for every photo in every duplicate group (see
 * {@code DuplicatesController#createDuplicateCell}), mirroring {@code FacePickerCellController}.
 */
@Component
@Scope("prototype")
public class DuplicateCellController {

    @FXML
    private VBox      cellRoot;
    @FXML
    private ImageView imageView;
    @FXML
    private Label     infoLabel;
    @FXML
    private CheckBox  checkBox;

    public void setThumbnail(Image image) {
        imageView.setImage(image);
    }

    public void setInfoText(String text) {
        infoLabel.setText(text);
    }

    public CheckBox checkBox() {
        return checkBox;
    }

    public VBox container() {
        return cellRoot;
    }
}
