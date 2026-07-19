package com.github.curiousoddman.curious_images.ui.controller.custom;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoTagRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.TagEmbeddingRecord;
import com.github.curiousoddman.curious_images.model.LoadedFxml;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.FxmlView;
import com.github.curiousoddman.curious_images.ui.nodes.photogrid.PhotoRowCell;
import javafx.beans.value.ObservableValue;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Controller for one visible row of the virtualized photo grid ({@code photo_grid_row.fxml}) — the
 * graphic of one {@link PhotoRowCell} ({@code ListView<PhotoGridRow>} cell).
 * <p>
 * Holds a pool of {@link PhotoCellController} image slots, grown lazily as the column count
 * increases and never shrunk — extra slots beyond the current row's photo count are just hidden
 * ({@link PhotoCellController#showEmpty()}), so growing the column count back up after a shrink
 * doesn't need to reload any FXML.
 * <p>
 * Because {@code ListView} only instantiates enough {@link PhotoRowCell}s to cover the visible
 * viewport plus a small buffer (and recycles them as the user scrolls), the total number of live
 * {@link PhotoCellController} instances stays bounded regardless of how many thousands of photos
 * are in the selection.
 * <p>
 * <b>Scope:</b> {@code prototype}, not the app's usual singleton {@code @Component} — a fresh
 * instance is created every time a {@link PhotoRowCell} loads {@code photo_grid_row.fxml}.
 */
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class PhotoGridRowController implements Initializable {

    private final FxmlLoader fxmlLoader;

    @FXML
    private HBox rowBox;

    private final List<PhotoCellController> pool = new ArrayList<>();

    private ObservableValue<Number>       thumbnailSize;
    private Consumer<PhotoRecord>         onPhotoClicked;
    private Function<PhotoRecord, String> tooltipTextFn;

    /**
     * Bumped on every {@link #showRow}. Lets a stale, in-flight background thumbnail/preview
     * lookup for a previous row assignment detect that this row controller has since been
     * recycled to a different set of photos, and discard its result instead of overwriting cells
     * that are now showing something else.
     */
    @Getter
    private long showToken;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // Cells are configured entirely via bindOnce()/showRow() below.
    }

    /**
     * Called once, right after this row controller is created — the thumbnail-size binding
     * target, click handler, tooltip-text function, and context-menu handlers are all
     * shared/stable for the lifetime of the row controller (only the photos shown change, via
     * {@link #showRow}).
     */
    public void bindOnce(ObservableValue<Number> thumbnailSize,
                         Consumer<PhotoRecord> onPhotoClicked,
                         Function<PhotoRecord, String> tooltipTextFn) {
        this.thumbnailSize = thumbnailSize;
        this.onPhotoClicked = onPhotoClicked;
        this.tooltipTextFn = tooltipTextFn;
    }

    public List<PhotoCellController> getCellControllers() {
        return List.copyOf(pool);
    }

    public void showRow(List<PhotoRecord> photos) {
        showToken++;
        ensurePoolSize(photos.size());

        for (int i = 0; i < pool.size(); i++) {
            PhotoCellController cell = pool.get(i);
            if (i < photos.size()) {
                PhotoRecord photo = photos.get(i);
                cell.showPlaceholder(photo, tooltipTextFn.apply(photo));
            } else {
                cell.showEmpty();
            }
        }
    }

    /**
     * Applies a looked-up image to whichever pool slot is currently showing {@code photo} — a
     * no-op if that slot has since been recycled to a different photo, or {@code photo} isn't
     * part of this row any more. Callers should also check {@link #getShowToken()} against a
     * captured value before calling this, to avoid the (harmless but wasted) lookup-vs-slot
     * mismatch entirely.
     */
    public void applyImage(PhotoRecord photo, Map<PhotoTagRecord, TagEmbeddingRecord> tags, Image image) {
        for (PhotoCellController cell : pool) {
            if (cell.getCurrentPhoto() == photo) {
                cell.showImage(photo, tags, image);
                return;
            }
        }
    }

    private void ensurePoolSize(int minSize) {
        while (pool.size() < minSize) {
            LoadedFxml<PhotoCellController> loaded     = fxmlLoader.load(FxmlView.PHOTO_CELL, null);
            PhotoCellController             controller = loaded.controller();
            controller.bindThumbnailSize(thumbnailSize);
            controller.setOnPhotoClicked(onPhotoClicked);
            pool.add(controller);
            rowBox.getChildren()
                  .add(loaded.parent());
        }
    }
}
