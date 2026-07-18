package com.github.curiousoddman.curious_images.ui.controller.custom;

import com.github.curiousoddman.curious_images.model.bundle.NotificationMenuItemBundle;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ResourceBundle;

@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class NotificationMenuItemController implements Initializable {
    @FXML
    public  VBox  detailsVbox;
    @FXML
    private Label messageLabel;

    private Runnable onDismiss;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        if (resources instanceof NotificationMenuItemBundle bundle) {
            messageLabel.setGraphic(bundle.getIcon());
            messageLabel.setText(bundle.getMessage());
            for (String detail : bundle.getDetails()) {
                Label l = new Label(detail);
                l.setStyle("-fx-text-fill: black;");
                detailsVbox.getChildren()
                           .add(l);
            }
            onDismiss = bundle.getOnDismiss();
        }
    }

    @FXML
    public void onDismissClicked(ActionEvent event) {
        onDismiss.run();
    }
}