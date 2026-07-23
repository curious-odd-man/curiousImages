package com.github.curiousoddman.curious_images.ui.nodes.photogrid;

import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaPhotoRecord;
import com.github.curiousoddman.curious_images.model.GridCellData;
import com.github.curiousoddman.curious_images.ui.controller.custom.GridCellController;
import com.github.curiousoddman.curious_images.ui.controller.custom.PhotoGridRowController;
import javafx.beans.value.ObservableValue;

import java.util.List;

/**
 * Everything a {@link PhotoRowCell} / {@link PhotoGridRowController} needs from the owning
 * controller ({@code LibraryController}) — kept as a narrow interface so the grid UI classes have
 * no direct dependency on repositories, jOOQ, or Spring, and can be pooled/recycled freely.
 */
public interface PhotoGridCallbacks {

    /**
     * Shared thumbnail-size slider value — bound once per pooled {@link GridCellController},
     * never rebound, so live slider drags resize already-rendered cells with no extra work.
     */
    ObservableValue<Number> thumbnailSizeProperty();

    /**
     * Single-click on a cell — expected to open the slideshow at this media's position.
     */
    void onPhotoClicked(MediaPhotoRecord photo);

    /**
     * A row just became visible showing {@code photos} (or was re-flowed to a different set while
     * still visible, e.g. after a resize). Implementations should kick off an async
     * thumbnail/quick-preview lookup for exactly these media IDs and apply the result via
     * {@code row.applyImage(...)}, and queue on-demand thumbnail generation for any that don't
     * have a real thumbnail yet.
     */
    void onRowShown(PhotoGridRowController row, List<GridCellData> photos);

    /**
     * The given row controller is no longer showing {@code previousPhotos} — about to show a
     * different row's photos, or has scrolled out of view. Implementations should drop any
     * bookkeeping keyed by those media IDs (e.g. a visible-cell registry used to swap in
     * newly-generated thumbnails without rebuilding anything).
     */
    void onRowHidden(PhotoGridRowController row, List<GridCellData> previousPhotos);
}
