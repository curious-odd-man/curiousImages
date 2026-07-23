package com.github.curiousoddman.curious_images.model;

import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaPhotoRecord;
import com.github.curiousoddman.curious_images.ui.util.AlertHelper;
import javafx.scene.Parent;

import java.util.List;

import static com.sun.javafx.util.Utils.runOnFxThread;

public record PhotoFailure(MediaPhotoRecord photo, String reason) {

    public static void displayAlert(List<PhotoFailure> failures, Parent parent) {
        StringBuilder sb = new StringBuilder();
        for (PhotoFailure failure : failures) {
            sb.append("• ")
              .append(failure.photo()
                             .getFilename())
              .append(" — ")
              .append(failure.reason())
              .append('\n');
        }
        runOnFxThread(() -> AlertHelper.showWarning(parent,
                "Some photos couldn't be moved to the recycle bin and were left in place",
                sb.toString()
                  .strip()));
    }
}
