package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceRecord;
import com.github.curiousoddman.curious_images.ui.styles.CssClasses;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static com.sun.javafx.util.Utils.runOnFxThread;

@Slf4j
@Component
@Scope("prototype")
public class FacePickerCellController implements Initializable {

    @FXML
    public StackPane         cellRoot;
    @FXML
    public ImageView         thumbImageView;
    @FXML
    public ProgressIndicator loadIndicator;
    @FXML
    public Label             coverBadge;

    private static Image noImageAvailable;

    private FaceRecord                         face;
    @Getter
    private boolean                            selected;
    private Consumer<FaceRecord>               onPrimaryClick;
    private Consumer<FaceRecord>               onToggleSelect;
    private BiConsumer<FaceRecord, MouseEvent> onContextMenuRequested;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        if (noImageAvailable == null) {
            noImageAvailable = new Image(
                    Objects.requireNonNull(getClass().getResourceAsStream("/img/noimage.png")));
        }
        cellRoot.setOnMouseClicked(this::handleClick);
    }

    private void handleClick(MouseEvent event) {
        if (face == null) {
            return;
        }
        if (event.getButton() == MouseButton.SECONDARY) {
            if (onContextMenuRequested != null) {
                onContextMenuRequested.accept(face, event);
            }
            return;
        }
        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        if (event.isControlDown() || event.isShiftDown()) {
            if (onToggleSelect != null) {
                onToggleSelect.accept(face);
            }
            return;
        }
        if (onPrimaryClick != null) {
            onPrimaryClick.accept(face);
        }
    }

    /**
     * Wires this cell up to display {@code face}, badging it as the current cover if
     * {@code isCover} is set. {@code onPrimaryClick} fires on a plain click (cover selection),
     * {@code onToggleSelect} on ctrl/shift-click (FR3 multi-select), and
     * {@code onContextMenuRequested} on right-click (FR1/FR2/FR5 single- or multi-face actions).
     */
    public void bind(FaceRecord face, boolean isCover,
                     Consumer<FaceRecord> onPrimaryClick,
                     Consumer<FaceRecord> onToggleSelect,
                     BiConsumer<FaceRecord, MouseEvent> onContextMenuRequested) {
        this.face = face;
        this.onPrimaryClick = onPrimaryClick;
        this.onToggleSelect = onToggleSelect;
        this.onContextMenuRequested = onContextMenuRequested;
        coverBadge.setVisible(isCover);
        setSelected(false);
        loadThumbnail(face);
    }

    /**
     * Updates this cell's selection highlight. Called by {@link FacePickerController} whenever
     * the dialog's multi-selection set changes (including deselecting everyone else on a plain
     * ctrl/shift toggle-off).
     */
    public void setSelected(boolean selected) {
        this.selected = selected;
        if (selected) {
            if (!cellRoot.getStyleClass()
                         .contains(CssClasses.FACE_CELL_SELECTED)) {
                cellRoot.getStyleClass()
                        .add(CssClasses.FACE_CELL_SELECTED);
            }
        } else {
            cellRoot.getStyleClass()
                    .remove(CssClasses.FACE_CELL_SELECTED);
        }
    }

    private void loadThumbnail(FaceRecord face) {
        loadIndicator.setVisible(true);
        thumbImageView.setImage(null);

        runOnDaemonThread("", () -> loadImage(thumbImageView, loadIndicator, face));
    }

    public static void loadImage(ImageView thumbImageView, ProgressIndicator loadIndicator, FaceRecord face) {
        Image img = Optional.ofNullable(face.getThumbnailAbsolutePath())
                            .map(Path::of)
                            .map(Path::toUri)
                            .map(uri -> new Image(uri.toString(), 0, 0, true, true, true))
                            .orElse(noImageAvailable);

        runOnFxThread(() -> {
            thumbImageView.setImage(img);
            loadIndicator.setVisible(false);
        });
    }
}

