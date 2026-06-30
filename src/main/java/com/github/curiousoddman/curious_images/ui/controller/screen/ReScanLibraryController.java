package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.model.bundle.RescanBundle;
import com.github.curiousoddman.curious_images.util.async.jobs.JobManager;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

@Lazy
@Component
@RequiredArgsConstructor
public class ReScanLibraryController implements Initializable {
    private final JobManager jobManager;

    @FXML
    public TextField path;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        if (resources instanceof RescanBundle bundle) {
            path.setText(bundle.getLibraryRoot());
        }
    }

    @FXML
    public void onChoosePath(ActionEvent actionEvent) {
        DirectoryChooser fileChooser = new DirectoryChooser();
        fileChooser.setTitle("Select library root directory");
        File result = fileChooser.showDialog(((Node) actionEvent.getSource()).getScene()
                                                                             .getWindow());
        path.setText(result.getAbsolutePath());
    }

    @FXML
    public void onRescan(ActionEvent actionEvent) {
        jobManager.submitImportJob(List.of(path.getText()));
        ((Stage) path.getScene()
                     .getWindow()).close();
    }
}
