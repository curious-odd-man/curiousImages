package com.github.curiousoddman.curious_images.domain.common.thumbnail;

import com.github.curiousoddman.curious_images.dbobj.tables.records.ThumbnailRecord;
import com.github.curiousoddman.curious_images.event.model.ThumbnailsReadyEvent;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
import com.github.curiousoddman.curious_images.ui.controller.custom.PhotoCellController;
import com.github.curiousoddman.curious_images.ui.controller.screen.LibraryController;
import javafx.scene.image.Image;
import lombok.experimental.UtilityClass;

import java.io.File;
import java.util.List;
import java.util.Map;

import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static com.sun.javafx.util.Utils.runOnFxThread;

@UtilityClass
public class ThumbnailUtils {
    public static void updateThumbnailImage(ThumbnailRepository repo, Map<Long, PhotoCellController> visiblePhotoCells, ThumbnailsReadyEvent event) {
        List<Long> ids = List.copyOf(event.getPhotoIds());
        runOnDaemonThread("ThumbnailUpdate", () -> {
            Map<Long, ThumbnailRecord> thumbs = repo.findByPhotoIds(ids);

            for (Map.Entry<Long, ThumbnailRecord> entry : thumbs.entrySet()) {
                PhotoCellController cell = visiblePhotoCells.get(entry.getKey());
                if (cell != null && hasCachedFile(entry.getValue())) {
                    runOnFxThread(() -> cell.showImage(cell.getCurrentPhoto(), loadThumbnailImage(entry.getValue())));
                }
            }
        });
    }

    /**
     * Loads a cached thumbnail file, capped to {@link #MAX_THUMBNAIL_DECODE_SIZE} rather than its
     * native on-disk resolution — the grid never displays it any larger than that, so there's no
     * reason to decode (and hold in memory) more pixels than that regardless of how the thumbnail
     * cache file itself was generated. Background-loading (last arg) keeps decoding off the FX
     * thread.
     */
    public static Image loadThumbnailImage(ThumbnailRecord thumbnail) {
        File file = new File(thumbnail.getCachePath());
        return new Image(file.toURI()
                             .toString(), LibraryController.MAX_THUMBNAIL_DECODE_SIZE, LibraryController.MAX_THUMBNAIL_DECODE_SIZE, true, true, true);
    }

    public static boolean hasCachedFile(ThumbnailRecord thumbnail) {
        return thumbnail.getCachePath() != null && new File(thumbnail.getCachePath()).isFile();
    }
}
