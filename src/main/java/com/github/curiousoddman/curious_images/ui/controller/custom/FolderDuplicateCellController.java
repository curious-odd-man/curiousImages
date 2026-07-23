package com.github.curiousoddman.curious_images.ui.controller.custom;

import com.github.curiousoddman.curious_images.model.bundle.FolderDuplicateCellBundle;
import com.github.curiousoddman.curious_images.util.HumanReadableUtils;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import lombok.Setter;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ResourceBundle;

/**
 * One side (one folder) of a folder-level duplicate pair — see folder_duplicate_cell.fxml.
 * Unlike {@link DuplicateCellController}, thumbnail tiles for this folder's photos aren't built
 * here: {@code FolderDuplicatesController} owns thumbnail loading/debouncing (the same pattern as
 * the file-level Duplicates tab, just keyed across two cells instead of N) and pushes nodes
 * straight into {@link #thumbnailFlowPane()}.
 */
@Component
@Scope("prototype")
public class FolderDuplicateCellController implements Initializable {

    @FXML
    public VBox     cellRoot;
    @FXML
    public CheckBox checkBox;
    @FXML
    public Label    folderPathLabel;
    @FXML
    public Label    summaryLabel;
    @FXML
    public FlowPane thumbnailFlowPane;

    @Setter
    private long folderId;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        if (resources instanceof FolderDuplicateCellBundle bundle) {
            folderPathLabel.setText(bundle.getFolderPath());
            summaryLabel.setText(
                    bundle.getGroupCount() + " duplicate group" + (bundle.getGroupCount() == 1 ? "" : "s")
                            + "  •  " + bundle.getPhotoCount() + " media" + (bundle.getPhotoCount() == 1 ? "" : "s")
                            + "  •  " + HumanReadableUtils.size(bundle.getTotalSize()));
            checkBox.setSelected(bundle.isInitiallyChecked());
        }
    }

    public long folderId() {
        return folderId;
    }

    public CheckBox checkBox() {
        return checkBox;
    }

    public VBox container() {
        return cellRoot;
    }

    public FlowPane thumbnailFlowPane() {
        return thumbnailFlowPane;
    }
}
