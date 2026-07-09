package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceRecord;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.function.Consumer;

import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static com.sun.javafx.util.Utils.runOnFxThread;

/**
 * Controller for one cell of {@code face_picker.fxml}'s grid — a single face thumbnail. Clicking
 * it reports the underlying {@link FaceRecord} back to {@link com.github.curiousoddman.curious_images.ui.controller.screen.FacePickerController}
 * via the callback passed to {@link #bind}. Thumbnail loading mirrors
 * {@code PersonDetailController#loadFaceThumbnail}.
 * <p>
 * <b>Scope:</b> {@code prototype}, not the app's usual singleton {@code @Component} — a single
 * dialog invocation instantiates one of these per face (via {@code FxmlLoader}, whose
 * controller factory resolves through the Spring context), so each cell needs its own instance
 * rather than all of them sharing one bean and clobbering each other's {@link #face}/{@link #onSelect}.
 */
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

    private FaceRecord           face;
    private Consumer<FaceRecord> onSelect;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        if (noImageAvailable == null) {
            noImageAvailable = new Image(
                    Objects.requireNonNull(getClass().getResourceAsStream("/img/noimage.png")));
        }
        cellRoot.setOnMouseClicked(event -> {
            if (onSelect != null && face != null) {
                onSelect.accept(face);
            }
        });
    }

    /**
     * Wires this cell up to display {@code face}, badging it as the current cover if
     * {@code isCover} is set, and reporting clicks to {@code onSelect}.
     */
    public void bind(FaceRecord face, boolean isCover, Consumer<FaceRecord> onSelect) {
        this.face = face;
        this.onSelect = onSelect;
        coverBadge.setVisible(isCover);
        loadThumbnail(face);
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
