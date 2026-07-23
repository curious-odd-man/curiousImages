package com.github.curiousoddman.curious_images.ui.controller.services;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.model.GridCellData;
import com.github.curiousoddman.curious_images.util.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class PhotoGridModel {
    private final AtomicLong generation = new AtomicLong();

    private List<GridCellData> photos     = List.of();
    private Map<Long, Integer> photoIndex = Map.of();

    public long nextGeneration() {
        return generation.incrementAndGet();
    }

    public long generation() {
        return generation.get();
    }

    public void setPhotos(List<GridCellData> photos) {
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
                     .map(GridCellData::photo)
                     .toList();
    }

    public int size() {
        return photos.size();
    }

    public List<GridCellData> photosSlice(int offset, int bound) {
        return photos.subList(offset, Math.min(bound, size()));
    }

    public Integer indexById(long id) {
        return photoIndex.get(id);
    }
}