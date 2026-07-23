package com.github.curiousoddman.curious_images.ui.util;

import com.github.curiousoddman.curious_images.domain.common.photo.PhotoRotationService;
import com.github.curiousoddman.curious_images.model.Media;
import com.github.curiousoddman.curious_images.util.ExplorerUtils;
import javafx.scene.Parent;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.ContextMenuEvent;
import lombok.RequiredArgsConstructor;
import org.kordamp.ikonli.javafx.FontIcon;
import org.springframework.stereotype.Component;

import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static org.kordamp.ikonli.bootstrapicons.BootstrapIcons.ARROW_CLOCKWISE;
import static org.kordamp.ikonli.bootstrapicons.BootstrapIcons.ARROW_COUNTERCLOCKWISE;
import static org.kordamp.ikonli.bootstrapicons.BootstrapIcons.ARROW_REPEAT;
import static org.kordamp.ikonli.bootstrapicons.BootstrapIcons.FOLDER_SYMLINK;

@Component
@RequiredArgsConstructor
public class GridContextMenu {

    private final PhotoRotationService photoRotationService;

    public ContextMenu show(Media media, Parent parent, ContextMenuEvent e) {
        if (media == null) {
            return null;
        }
        ContextMenu contextMenu = new ContextMenu();

        FontIcon graphicArrowClockwise = new FontIcon(ARROW_CLOCKWISE);
        MenuItem rotateCw              = new MenuItem("Rotate 90°", graphicArrowClockwise);
        rotateCw.setOnAction(ev -> rotateCurrentPhoto(media, PhotoRotationService.ROTATE_CW));

        FontIcon graphicArrowCounterClockwise = new FontIcon(ARROW_COUNTERCLOCKWISE);
        MenuItem rotateCcw                    = new MenuItem("Rotate 90°", graphicArrowCounterClockwise);
        rotateCcw.setOnAction(ev -> rotateCurrentPhoto(media, PhotoRotationService.ROTATE_CCW));

        MenuItem rotate180 = new MenuItem("Rotate 180°", new FontIcon(ARROW_REPEAT));
        rotate180.setOnAction(ev -> rotateCurrentPhoto(media, PhotoRotationService.ROTATE_180));

        MenuItem reveal = new MenuItem("Reveal in Explorer", new FontIcon(FOLDER_SYMLINK));
        reveal.setOnAction(ev -> ExplorerUtils.revealInExplorer(media.getAbsolutePath()));

        contextMenu.getItems()
                   .addAll(rotateCw, rotateCcw, rotate180, new SeparatorMenuItem(), reveal);
        contextMenu.show(parent, e.getScreenX(), e.getScreenY());
        return contextMenu;
    }

    private void rotateCurrentPhoto(Media media, int deltaDegrees) {
        runOnDaemonThread("RotatePhoto", () -> photoRotationService.rotateAndClearAiResults(media.getId(), deltaDegrees));
    }
}
