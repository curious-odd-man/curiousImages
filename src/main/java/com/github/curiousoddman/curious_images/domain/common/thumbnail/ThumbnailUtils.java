package com.github.curiousoddman.curious_images.domain.common.thumbnail;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoTagRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.TagEmbeddingRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ThumbnailRecord;
import com.github.curiousoddman.curious_images.event.model.ThumbnailsReadyEvent;
import com.github.curiousoddman.curious_images.persistence.PhotoTagRepository;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
import com.github.curiousoddman.curious_images.ui.controller.custom.PhotoCellController;
import javafx.scene.image.Image;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.Map;

import static com.github.curiousoddman.curious_images.ui.controller.custom.PhotoGridController.MAX_THUMBNAIL_DECODE_SIZE;
import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static com.sun.javafx.util.Utils.runOnFxThread;

@Slf4j
@Component
@RequiredArgsConstructor
public class ThumbnailUtils {
    private final ThumbnailRepository thumbnailRepository;
    private final PhotoTagRepository  photoTagRepository;

    public void updateThumbnailImage(Map<Long, PhotoCellController> visiblePhotoCells, ThumbnailsReadyEvent event) {
        List<Long> ids = List.copyOf(event.getPhotoIds());
        runOnDaemonThread("ThumbnailUpdate", () -> {
            Map<Long, ThumbnailRecord>                         thumbs = thumbnailRepository.findByPhotoIds(ids);
            Map<Long, Map<PhotoTagRecord, TagEmbeddingRecord>> tags   = photoTagRepository.findForPhotos(ids);

            for (Map.Entry<Long, ThumbnailRecord> entry : thumbs.entrySet()) {
                PhotoCellController cell          = visiblePhotoCells.get(entry.getKey());
                boolean             hasCachedFile = hasCachedFile(entry.getValue());
                if (cell != null && hasCachedFile) {

                    runOnFxThread(() -> cell.showImage(
                            cell.getCurrentPhoto(),
                            tags.get(entry.getKey()),
                            loadThumbnailImage(entry.getValue())
                    ));
                } else {
                    log.warn("Unable to display thumbnail: {} --> {} && {}", event, cell != null, hasCachedFile);
                }
            }
        });
    }

    public static Image loadThumbnailImage(ThumbnailRecord thumbnail) {
        File file = new File(thumbnail.getCachePath());
        return new Image(file.toURI()
                             .toString(), MAX_THUMBNAIL_DECODE_SIZE, MAX_THUMBNAIL_DECODE_SIZE, true, true, true);
    }

    public static boolean hasCachedFile(ThumbnailRecord thumbnail) {
        return thumbnail.getCachePath() != null && new File(thumbnail.getCachePath()).isFile();
    }
}
