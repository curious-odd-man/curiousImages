package com.github.curiousoddman.curious_images.ui.controller.services;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.persistence.AlbumPhotoRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoRepository;
import com.github.curiousoddman.curious_images.ui.controller.custom.PhotoGridController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static com.sun.javafx.util.Utils.runOnFxThread;

@Slf4j
@Component
@RequiredArgsConstructor
public class PhotoGridManager {
    private final PhotoRepository      photoRepository;
    private final AlbumPhotoRepository albumPhotoRepository;

    private PhotoGridController photoGridController;

    public void initialize(PhotoGridController photoGridController) {
        this.photoGridController = photoGridController;
    }

    public void populate(List<PhotoRecord> photos) {
        photoGridController.populatePhotoGrid(photos);
    }

    public void clear() {
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

    private void loadSelectionResult(long myGeneration, List<PhotoRecord> photos) {
        runOnFxThread(() -> {
            if (myGeneration == photoGridController.currentChange()) {
                populate(photos);
            }
        });
    }
}
