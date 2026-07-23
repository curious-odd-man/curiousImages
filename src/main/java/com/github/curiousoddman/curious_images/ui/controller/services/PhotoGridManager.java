package com.github.curiousoddman.curious_images.ui.controller.services;

import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaPhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaTagRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.TagEmbeddingRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ThumbnailRecord;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.ThumbnailUtils;
import com.github.curiousoddman.curious_images.model.GridCellData;
import com.github.curiousoddman.curious_images.model.Media;
import com.github.curiousoddman.curious_images.model.PersonDetails;
import com.github.curiousoddman.curious_images.persistence.AlbumPhotoRepository;
import com.github.curiousoddman.curious_images.persistence.DuplicateGroupRepository;
import com.github.curiousoddman.curious_images.persistence.MediaRepository;
import com.github.curiousoddman.curious_images.persistence.PersonRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoTagRepository;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
import com.github.curiousoddman.curious_images.ui.controller.custom.GridController;
import com.github.curiousoddman.curious_images.util.async.DelayedAction;
import com.github.curiousoddman.curious_images.util.async.jobs.JobManager;
import javafx.scene.image.Image;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.github.curiousoddman.curious_images.domain.common.thumbnail.ThumbnailUtils.hasCachedFile;
import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static com.sun.javafx.util.Utils.runOnFxThread;

@Slf4j
@Component
@RequiredArgsConstructor
public class PhotoGridManager {
    private static final int THUMBNAIL_GEN_DEBOUNCE_MS = 150;

    private final MediaRepository          mediaRepository;
    private final AlbumPhotoRepository     albumPhotoRepository;
    private final PhotoTagRepository       photoTagRepository;
    private final PersonRepository         personRepository;
    private final DuplicateGroupRepository duplicateGroupRepository;
    private final ThumbnailRepository      thumbnailRepository;
    private final JobManager               jobManager;

    private final Set<Long>     pendingThumbnailGenIds = new HashSet<>();
    private final DelayedAction thumbnailGenDebounce   = new DelayedAction(THUMBNAIL_GEN_DEBOUNCE_MS, TimeUnit.MILLISECONDS);

    private GridController gridController;

    public void initialize(GridController gridController) {
        this.gridController = gridController;
    }

    public void populate(List<MediaPhotoRecord> photos) {
        List<GridCellData> data = createData(photos);
        runOnFxThread(() -> gridController.populatePhotoGrid(data));
    }

    public void clear() {
        pendingThumbnailGenIds.clear();
        gridController.clear();
    }

    public void loadPhotosForFolder(long folderId) {
        long myGeneration = gridController.initiateChange();
        runOnDaemonThread("LoadFolder", () -> {
            List<MediaPhotoRecord> photos = mediaRepository.findByFolderId(folderId);
            loadSelectionResult(myGeneration, photos);
        });
    }

    public void loadPhotosForTimeline(int year, int month, Integer day) {
        long myGeneration = gridController.initiateChange();
        runOnDaemonThread("LoadTimeline", () -> {
            List<Media> photos = mediaRepository.findByCaptureDate(year, month, day);
            loadSelectionResult(myGeneration, photos.stream()
                                                    .map(Media::photo)
                                                    .toList());
        });
    }

    public void loadPhotosUndated() {
        long myGeneration = gridController.initiateChange();
        runOnDaemonThread("LoadUndated", () -> {
            List<MediaPhotoRecord> photos = mediaRepository.findByNullCaptureDate();
            loadSelectionResult(myGeneration, photos);
        });
    }

    public void loadPhotosForAlbum(long albumId) {
        long myGeneration = gridController.initiateChange();
        runOnDaemonThread("LoadAlbum", () -> {
            List<Long> photoIds = albumPhotoRepository.findPhotoIdsByAlbumId(albumId);
            List<MediaPhotoRecord> photos = photoIds.stream()
                                                    .map(id -> mediaRepository.findById(id)
                                                                              .orElse(null))
                                                    .filter(Objects::nonNull)
                                                    .toList();
            loadSelectionResult(myGeneration, photos);
        });
    }

    public List<GridCellData> createData(List<MediaPhotoRecord> photos) {
        List<Long> ids = photos.stream()
                               .map(MediaPhotoRecord::getId)
                               .toList();

        Map<Long, Map<MediaTagRecord, TagEmbeddingRecord>> tags       = photoTagRepository.findForPhotos(ids);
        Map<Long, List<PersonDetails>>                     persons    = personRepository.findByPhotoIdsIn(ids);
        Map<Long, Integer>                                 duplicates = duplicateGroupRepository.countDuplicatesForPhotos(ids);
        Map<Long, ThumbnailRecord>                         thumbs     = thumbnailRepository.findByPhotoIds(ids);

        List<Long> missing = new ArrayList<>();
        List<GridCellData> cellData = photos
                .stream()
                .map(mediaPhotoRecord -> {
                    ThumbnailRecord thumbnail  = thumbs.get(mediaPhotoRecord.getId());
                    Image           thumbImage = null;
                    if (thumbnail != null && hasCachedFile(thumbnail)) {
                        thumbImage = ThumbnailUtils.loadThumbnailImage(thumbnail);
                    } else {
                        missing.add(mediaPhotoRecord.getId());
                    }
                    return new GridCellData(
                            Media.photo(mediaPhotoRecord),
                            thumbImage,
                            tags.getOrDefault(mediaPhotoRecord.getId(), Map.of()),
                            persons.getOrDefault(mediaPhotoRecord.getId(), List.of()),
                            duplicates.getOrDefault(mediaPhotoRecord.getId(), 0) > 1

                    );
                })
                .toList();

        if (!missing.isEmpty()) {
            queueThumbnailGeneration(missing);
        }

        return cellData;
    }

    private void queueThumbnailGeneration(List<Long> ids) {
        pendingThumbnailGenIds.addAll(ids);
        thumbnailGenDebounce.reSchedule(() -> {
            if (pendingThumbnailGenIds.isEmpty()) {
                return;
            }
            List<Long> batch = List.copyOf(pendingThumbnailGenIds);
            pendingThumbnailGenIds.clear();
            jobManager.submitThumbnailGenerationJob(batch);
        });
    }

    private void loadSelectionResult(long myGeneration, List<MediaPhotoRecord> photos) {
        if (myGeneration == gridController.currentChange()) {
            populate(photos);
        }
    }
}
