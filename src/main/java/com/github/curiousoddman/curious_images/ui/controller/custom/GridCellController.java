package com.github.curiousoddman.curious_images.ui.controller.custom;

import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaPhotoRecord;
import com.github.curiousoddman.curious_images.model.GridCellData;
import com.github.curiousoddman.curious_images.model.PersonDetails;
import com.github.curiousoddman.curious_images.model.bundle.GridCellResources;
import com.github.curiousoddman.curious_images.ui.util.GridContextMenu;
import javafx.beans.value.ObservableValue;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.javafx.FontIcon;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.Comparator;
import java.util.ResourceBundle;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.github.curiousoddman.curious_images.ui.util.UiUtils.fxManage;
import static com.github.curiousoddman.curious_images.ui.util.UiUtils.fxUnmanage;
import static com.github.curiousoddman.curious_images.ui.util.UiUtils.registerHoverTooltip;
import static com.github.curiousoddman.curious_images.ui.util.UiUtils.registerZoomInOnHover;
import static com.github.curiousoddman.curious_images.util.HumanReadableUtils.gps;
import static com.github.curiousoddman.curious_images.util.HumanReadableUtils.rate;
import static com.github.curiousoddman.curious_images.util.HumanReadableUtils.size;

@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class GridCellController implements Initializable {
    private final Tooltip iconsTooltip = new Tooltip();

    private final GridContextMenu gridContextMenu;

    @FXML
    public  BorderPane cellRoot;
    @FXML
    public  Label      typeImageIcon;
    @FXML
    public  Label      tagIcon;
    @FXML
    public  Label      gpsIcon;
    @FXML
    public  Label      faceCountLabel;
    @FXML
    public  Label      faceIcon;
    @FXML
    public  FontIcon   duplicateIcon;
    @FXML
    public  FontIcon   showInfoIcon;
    @FXML
    public  HBox       iconsHbox;
    @FXML
    private StackPane  imageSlot;
    @FXML
    private Rectangle  placeholderRect;
    @FXML
    private Label      placeholderLabel;
    @FXML
    private ImageView  imageView;

    @Setter
    private Consumer<MediaPhotoRecord> onPhotoClicked;

    @Getter
    private GridCellData gridCellData;

    private Consumer<GridCellData> imageDetailsConsumer;

    private Tooltip cellTooltip;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        log.debug("Initialize");
        registerZoomInOnHover(imageView, showInfoIcon);
        cellTooltip = new Tooltip("");
        cellTooltip.getStyleClass()
                   .add("monospace-text");
        Tooltip.install(imageView, cellTooltip);
        cellTooltip.setShowDelay(Duration.millis(500));
        imageView.setPreserveRatio(true);

        cellRoot.setOnContextMenuRequested(e -> gridContextMenu.show(gridCellData.media(), cellRoot, e));
        if (resources instanceof GridCellResources cellResources) {
            imageDetailsConsumer = cellResources.getImageDetailsConsumer();
        }
    }

    public void bindThumbnailSize(ObservableValue<? extends Number> size) {
        log.debug("binding dimensions");
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

    public void showPlaceholder(GridCellData data) {
        log.debug("Placeholder.... {}", data.mediaId());
        this.gridCellData = data;
        fxManage(cellRoot, placeholderRect, placeholderLabel);
        fxUnmanage(imageView, iconsHbox);
        cellTooltip.setText(data.tooltipText());
        imageView.setImage(null);
    }

    public void showEmpty() {
        log.debug("Disappear {}", gridCellData == null ? null : gridCellData.mediaId());
        this.gridCellData = null;
        fxUnmanage(cellRoot, iconsHbox);
        imageView.setImage(null);
    }

    public void showImage(GridCellData data) {
        log.debug("Showing all data... {}", gridCellData.mediaId());
        MediaPhotoRecord media = data.photo();
        if (gridCellData.photo() != media) {
            log.debug("oops, media changed..");
            return;
        }

        if (data.image() == null) {
            fxManage(placeholderRect, placeholderLabel);
            fxUnmanage(imageView);
        } else {
            fxManage(imageView);
            fxUnmanage(placeholderRect, placeholderLabel);
            imageView.setImage(data.image());
        }

        fxManage(imageView, iconsHbox);

        fxManage(!data.tags()
                      .isEmpty(), tagIcon);
        boolean hasGps = media.getGpsAltitude() != null || media.getGpsLat() != null || media.getGpsLon() != null;
        fxManage(hasGps, gpsIcon);
        if (data.persons()
                .size() > 1) {
            faceCountLabel.setText(String.valueOf(data.persons()
                                                      .size()));
            fxManage(faceCountLabel);
        } else {
            fxUnmanage(faceCountLabel);
        }
        fxManage(!data.persons()
                      .isEmpty(), faceIcon);
        fxManage(data.hasDuplicates(), duplicateIcon);

        registerHoverTooltip(iconsTooltip, "Has duplicates", duplicateIcon);
        registerHoverTooltip(iconsTooltip, media.getExtension() + ": " + size(media.getFileSize()), typeImageIcon);
        registerHoverTooltip(iconsTooltip, data.tags()
                                               .entrySet()
                                               .stream()
                                               .map(v -> new TagData(v.getValue()
                                                                      .getTag(), v.getKey()
                                                                                  .getConfidence()))
                                               .sorted(Comparator.comparing(TagData::score))
                                               .map(e -> e.name() + " (" + rate(e.score()) + ")")
                                               .collect(Collectors.joining("\n")), tagIcon);
        registerHoverTooltip(iconsTooltip, gps(media.getGpsLat(), media.getGpsLon()), gpsIcon);
        registerHoverTooltip(iconsTooltip, data.persons()
                                               .stream()
                                               .map(PersonDetails::personName)
                                               .collect(Collectors.joining("\n")), faceCountLabel, faceIcon);
    }

    private record TagData(String name, double score) {

    }

    @FXML
    private void onCellClicked(MouseEvent e) {
        if (e.getClickCount() == 1 && e.getButton() == MouseButton.PRIMARY && gridCellData != null && onPhotoClicked != null) {
            onPhotoClicked.accept(gridCellData.photo());
        }
    }

    @FXML
    public void onShowInfo(ActionEvent event) {
        imageDetailsConsumer.accept(gridCellData);
    }
}
