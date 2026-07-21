package com.github.curiousoddman.curious_images.ui.controller.services;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.model.PhotoCellData;
import com.github.curiousoddman.curious_images.util.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class PhotoGridModel {
    private final AtomicLong generation = new AtomicLong();

    private List<PhotoCellData> photos     = List.of();
    private Map<Long, Integer>  photoIndex = Map.of();

    public long nextGeneration() {
        return generation.incrementAndGet();
    }

    public long generation() {
        return generation.get();
    }

    public void setPhotos(List<PhotoCellData> photos) {
        this.photos = List.copyOf(photos);
        this.photoIndex = CollectionUtils.getIdToIndexMap(photos, r -> r.photo()
                                                                        .getId());
    }

    public void clear() {
        photos = List.of();
        photoIndex = Map.of();
        nextGeneration();
    }

    public List<PhotoRecord> photos() {
        return photos.stream()
                     .map(PhotoCellData::photo)
                     .toList();
    }

    public int size() {
        return photos.size();
    }

    public List<PhotoCellData> photosSlice(int offset, int bound) {
        return photos.subList(offset, Math.min(bound, size()));
    }

    public Integer indexById(long id) {
        return photoIndex.get(id);
    }
}