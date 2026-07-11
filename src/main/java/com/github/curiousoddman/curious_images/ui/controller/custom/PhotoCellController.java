package com.github.curiousoddman.curious_images.ui.controller.custom;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import javafx.beans.value.ObservableValue;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ResourceBundle;
import java.util.function.Consumer;

/**
 * Controller for a single image slot in the virtualized photo grid ({@code photo_cell.fxml}).
 * <p>
 * Instances are pooled and reused by {@link PhotoGridRowController} — a given instance shows a
 * different photo every time its row scrolls to a new position or the grid is re-flowed after a
 * resize / thumbnail-size change. This is what replaces the old {@code FlowPane} approach of
 * creating one permanent {@code Node} per photo: the number of live instances is now bounded by
 * (visible rows × columns), not by the size of the selection.
 * <p>
 * <b>Scope:</b> {@code prototype}, not the app's usual singleton {@code @Component} — a fresh
 * instance is created every time the pool grows (see {@code PhotoGridRowController#ensurePoolSize}),
 * mirroring {@code FacePickerCellController}/{@code DuplicateCellController}.
 */
@Component
@Scope("prototype")
public class PhotoCellController implements Initializable {

    @FXML
    private Label     cellRoot;
    @FXML
    private StackPane imageSlot;
    @FXML
    private Rectangle placeholderRect;
    @FXML
    private Label     placeholderLabel;
    @FXML
    private ImageView imageView;
    @FXML
    private Tooltip   tooltip;

    private Consumer<PhotoRecord> onPhotoClicked;
    private PhotoRecord           currentPhoto;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        tooltip.setShowDelay(Duration.millis(500));
        imageView.setPreserveRatio(true);
    }

    /**
     * Binds this cell's image/placeholder size to the shared thumbnail-size slider. Called once,
     * right after this controller is created — the binding then tracks the slider live (including
     * mid-drag) for every photo this pooled instance ever shows, with no rebinding needed.
     */
    public void bindThumbnailSize(ObservableValue<? extends Number> size) {
        cellRoot.prefWidthProperty()
                .bind(size);
        imageSlot.prefWidthProperty()
                 .bind(size);
        imageSlot.prefHeightProperty()
                 .bind(size);
        placeholderRect.widthProperty()
                       .bind(size);
        placeholderRect.heightProperty()
                       .bind(size);
        imageView.fitWidthProperty()
                 .bind(size);
        imageView.fitHeightProperty()
                 .bind(size);
    }

    /**
     * Set once at creation by {@link PhotoGridRowController} — fired on single click, for as long
     * as this pooled instance lives.
     */
    public void setOnPhotoClicked(Consumer<PhotoRecord> onPhotoClicked) {
        this.onPhotoClicked = onPhotoClicked;
    }

    /**
     * Shows this slot as the "Loading..." placeholder for {@code photo} — the real thumbnail or
     * quick-preview follows asynchronously via {@link #showImage}. No disk/DB I/O here.
     */
    public void showPlaceholder(PhotoRecord photo, String tooltipText) {
        this.currentPhoto = photo;
        cellRoot.setVisible(true);
        cellRoot.setManaged(true);
        cellRoot.setText(photo.getFilename());
        tooltip.setText(tooltipText);
        imageView.setVisible(false);
        imageView.setManaged(false);
        imageView.setImage(null);
        placeholderRect.setVisible(true);
        placeholderLabel.setVisible(true);
    }

    /**
     * Swaps in the real image (thumbnail, quick-preview, or a fallback icon) — only if this slot
     * is still showing {@code photo}; a no-op otherwise, i.e. this pooled instance has since been
     * recycled to a different photo and the caller's lookup is stale.
     */
    public void showImage(PhotoRecord photo, Image image) {
        if (currentPhoto != photo) {
            return;
        }
        imageView.setImage(image);
        imageView.setVisible(true);
        imageView.setManaged(true);
        placeholderRect.setVisible(false);
        placeholderLabel.setVisible(false);
    }

    /**
     * Hides this pool slot entirely — used for unused slots in a partially-filled last row.
     */
    public void showEmpty() {
        this.currentPhoto = null;
        cellRoot.setVisible(false);
        cellRoot.setManaged(false);
        imageView.setImage(null);
    }

    public PhotoRecord getCurrentPhoto() {
        return currentPhoto;
    }

    @FXML
    private void onCellClicked(MouseEvent e) {
        if (e.getClickCount() == 1 && currentPhoto != null && onPhotoClicked != null) {
            onPhotoClicked.accept(currentPhoto);
        }
    }
}
