package com.github.curiousoddman.curious_images.ui.controller.custom;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoTagRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.TagEmbeddingRecord;
import com.github.curiousoddman.curious_images.ui.util.ImageContextMenu;
import com.github.curiousoddman.curious_images.util.HumanReadableUtils;
import javafx.beans.value.ObservableValue;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.function.Consumer;

import static com.github.curiousoddman.curious_images.ui.styles.CssClasses.TAG_LABEL;
import static com.github.curiousoddman.curious_images.ui.util.UiUtils.fxManage;
import static com.github.curiousoddman.curious_images.ui.util.UiUtils.fxUnmanage;

@Component
@Scope("prototype")
@RequiredArgsConstructor
public class PhotoCellController implements Initializable {

    private final ImageContextMenu imageContextMenu;

    @FXML
    public  BorderPane cellRoot;
    @FXML
    public  Label      filenameLabel;
    @FXML
    public  FlowPane   tagsPane;
    @FXML
    private StackPane  imageSlot;
    @FXML
    private Rectangle  placeholderRect;
    @FXML
    private Label      placeholderLabel;
    @FXML
    private ImageView  imageView;

    @Setter
    private Consumer<PhotoRecord> onPhotoClicked;

    @Getter
    private PhotoRecord currentPhoto;

    private Tooltip tooltip;


    @Override
    public void initialize(URL location, ResourceBundle resources) {
        tooltip = new Tooltip("");
        tooltip.getStyleClass()
               .add("monospace-text");
        Tooltip.install(cellRoot, tooltip);
        tooltip.setShowDelay(Duration.millis(500));
        imageView.setPreserveRatio(true);

        cellRoot.setOnContextMenuRequested(e -> imageContextMenu.show(currentPhoto, cellRoot, e));
    }

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
        tagsPane.prefWrapLengthProperty()
                .bind(cellRoot.widthProperty());
    }

    /**
     * Shows this slot as the "Loading..." placeholder for {@code photo} — the real thumbnail or
     * quick-preview follows asynchronously via {@link #showImage}. No disk/DB I/O here.
     */
    public void showPlaceholder(PhotoRecord photo, String tooltipText) {
        this.currentPhoto = photo;
        fxManage(cellRoot, filenameLabel, placeholderRect);
        fxUnmanage(imageView, tagsPane);
        filenameLabel.setText(photo.getFilename());
        tooltip.setText(tooltipText);
        imageView.setImage(null);
        placeholderRect.setVisible(true);
        placeholderLabel.setVisible(true);
    }

    public void showImage(PhotoRecord photo, Map<PhotoTagRecord, TagEmbeddingRecord> tags, Image image) {
        if (currentPhoto != photo) {
            return;
        }
        imageView.setImage(image);
        fxManage(imageView, tagsPane);
        ObservableList<Node> tagsParent = tagsPane.getChildren();
        tagsParent.clear();
        for (Map.Entry<PhotoTagRecord, TagEmbeddingRecord> entry : tags.entrySet()) {
            PhotoTagRecord     tag       = entry.getKey();
            TagEmbeddingRecord embedding = entry.getValue();
            Label              label     = new Label(embedding.getTag() + " (" + HumanReadableUtils.rate(tag.getConfidence()) + ")");
            label.getStyleClass().add(TAG_LABEL);
            tagsParent.add(label);
        }
        placeholderRect.setVisible(false);
        placeholderLabel.setVisible(false);
    }

    /**
     * Hides this pool slot entirely — used for unused slots in a partially-filled last row.
     */
    public void showEmpty() {
        this.currentPhoto = null;
        filenameLabel.setVisible(false);
        fxUnmanage(cellRoot);
        imageView.setImage(null);
    }

    @FXML
    private void onCellClicked(MouseEvent e) {
        if (e.getClickCount() == 1 && e.getButton() == MouseButton.PRIMARY && currentPhoto != null && onPhotoClicked != null) {
            onPhotoClicked.accept(currentPhoto);
        }
    }
}
