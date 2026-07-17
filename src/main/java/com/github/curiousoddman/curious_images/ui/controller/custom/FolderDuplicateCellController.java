package com.github.curiousoddman.curious_images.ui.controller.custom;

import com.github.curiousoddman.curious_images.model.bundle.FolderDuplicateCellBundle;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
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

    private long folderId;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        if (resources instanceof FolderDuplicateCellBundle bundle) {
            folderPathLabel.setText(bundle.getFolderPath());
            summaryLabel.setText(
                    bundle.getGroupCount() + " duplicate group" + (bundle.getGroupCount() == 1 ? "" : "s")
                            + "  •  " + bundle.getPhotoCount() + " photo" + (bundle.getPhotoCount() == 1 ? "" : "s")
                            + "  •  " + humanReadableSize(bundle.getTotalSize()));
            checkBox.setSelected(bundle.isInitiallyChecked());
        }
    }

    private static String humanReadableSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double   value = bytes / 1024.0;
        int      i     = 0;
        while (value >= 1024.0 && i < units.length - 1) {
            value /= 1024.0;
            i++;
        }
        return String.format("%.1f %s", value, units[i]);
    }

    public void setFolderId(long folderId) {
        this.folderId = folderId;
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
