package com.github.curiousoddman.curious_images.ui.controller.services;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoTagRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.TagEmbeddingRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ThumbnailRecord;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.ThumbnailUtils;
import com.github.curiousoddman.curious_images.model.PersonDetails;
import com.github.curiousoddman.curious_images.model.PhotoCellData;
import com.github.curiousoddman.curious_images.persistence.AlbumPhotoRepository;
import com.github.curiousoddman.curious_images.persistence.DuplicateGroupRepository;
import com.github.curiousoddman.curious_images.persistence.PersonRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoTagRepository;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
import com.github.curiousoddman.curious_images.ui.controller.custom.PhotoGridController;
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

    private final PhotoRepository          photoRepository;
    private final AlbumPhotoRepository     albumPhotoRepository;
    private final PhotoTagRepository       photoTagRepository;
    private final PersonRepository         personRepository;
    private final DuplicateGroupRepository duplicateGroupRepository;
    private final ThumbnailRepository      thumbnailRepository;
    private final JobManager               jobManager;

    private final Set<Long>     pendingThumbnailGenIds = new HashSet<>();
    private final DelayedAction thumbnailGenDebounce   = new DelayedAction(THUMBNAIL_GEN_DEBOUNCE_MS, TimeUnit.MILLISECONDS);

    private PhotoGridController photoGridController;

    public void initialize(PhotoGridController photoGridController) {
        this.photoGridController = photoGridController;
    }

    public void populate(List<PhotoRecord> photos) {
        List<PhotoCellData> data = createData(photos);
        runOnFxThread(() -> photoGridController.populatePhotoGrid(data));
    }

    public void clear() {
        pendingThumbnailGenIds.clear();
        photoGridController.clear();
    }

    public void loadPhotosForFolder(long folderId) {
        long myGeneration = photoGridController.initiateChange();
        runOnDaemonThread("LoadFolder", () -> {
            List<PhotoRecord> photos = photoRepository.findByFolderId(folderId);
            loadSelectionResult(myGeneration, photos);
        });
    }

    public void loadPhotosForTimeline(int year, int month, Integer day) {
        long myGeneration = photoGridController.initiateChange();
        runOnDaemonThread("LoadTimeline", () -> {
            List<PhotoRecord> photos = photoRepository.findByCaptureDate(year, month, day);
            loadSelectionResult(myGeneration, photos);
        });
    }

    public void loadPhotosUndated() {
        long myGeneration = photoGridController.initiateChange();
        runOnDaemonThread("LoadUndated", () -> {
            List<PhotoRecord> photos = photoRepository.findByNullCaptureDate();
            loadSelectionResult(myGeneration, photos);
        });
    }

    public void loadPhotosForAlbum(long albumId) {
        long myGeneration = photoGridController.initiateChange();
        runOnDaemonThread("LoadAlbum", () -> {
            List<Long> photoIds = albumPhotoRepository.findPhotoIdsByAlbumId(albumId);
            List<PhotoRecord> photos = photoIds.stream()
                                               .map(id -> photoRepository.findById(id)
                                                                         .orElse(null))
                                               .filter(Objects::nonNull)
                                               .toList();
            loadSelectionResult(myGeneration, photos);
        });
    }

    public List<PhotoCellData> createData(List<PhotoRecord> photos) {
        List<Long> ids = photos.stream()
                               .map(PhotoRecord::getId)
                               .toList();

        Map<Long, Map<PhotoTagRecord, TagEmbeddingRecord>> tags       = photoTagRepository.findForPhotos(ids);
        Map<Long, List<PersonDetails>>                     persons    = personRepository.findByPhotoIdsIn(ids);
        Map<Long, Integer>                                 duplicates = duplicateGroupRepository.countDuplicatesForPhotos(ids);
        Map<Long, ThumbnailRecord>                         thumbs     = thumbnailRepository.findByPhotoIds(ids);

        List<Long> missing = new ArrayList<>();
        List<PhotoCellData> cellData = photos
                .stream()
                .map(photoRecord -> {
                    ThumbnailRecord thumbnail  = thumbs.get(photoRecord.getId());
                    Image           thumbImage = null;
                    if (thumbnail != null && hasCachedFile(thumbnail)) {
                        thumbImage = ThumbnailUtils.loadThumbnailImage(thumbnail);
                    } else {
                        missing.add(photoRecord.getId());
                    }
                    return new PhotoCellData(
                            photoRecord,
                            thumbImage,
                            tags.getOrDefault(photoRecord.getId(), Map.of()),
                            persons.getOrDefault(photoRecord.getId(), List.of()),
                            duplicates.getOrDefault(photoRecord.getId(), 0) > 1

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

    private void loadSelectionResult(long myGeneration, List<PhotoRecord> photos) {
        if (myGeneration == photoGridController.currentChange()) {
            populate(photos);
        }
    }
}
