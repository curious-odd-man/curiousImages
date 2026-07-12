package com.github.curiousoddman.curious_images.ui.controller.custom;

import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component
@Scope("prototype")
public class DuplicateCellController {

    @FXML
    public  Label     placeholderLabel;
    @FXML
    public  Rectangle placeholderRect;
    @FXML
    private VBox      cellRoot;
    @FXML
    private ImageView imageView;
    @FXML
    private Label     infoLabel;
    @FXML
    private CheckBox  checkBox;

    public void setThumbnail(Image image) {
        imageView.setVisible(image != null);
        placeholderLabel.setVisible(image == null);
        placeholderRect.setVisible(image == null);
        if (image != null) {
            imageView.setImage(image);
        }
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
