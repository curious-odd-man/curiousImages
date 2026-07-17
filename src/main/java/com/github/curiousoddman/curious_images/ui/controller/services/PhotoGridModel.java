package com.github.curiousoddman.curious_images.ui.controller.services;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.util.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class PhotoGridModel {
    private final AtomicLong generation = new AtomicLong();

    private List<PhotoRecord>  photos     = List.of();
    private Map<Long, Integer> photoIndex = Map.of();

    public long nextGeneration() {
        return generation.incrementAndGet();
    }

    public long generation() {
        return generation.get();
    }

    public void setPhotos(List<PhotoRecord> photos) {
        this.photos = List.copyOf(photos);
        this.photoIndex = CollectionUtils.getIdToIndexMap(photos);
    }

    public void clear() {
        photos = List.of();
        photoIndex = Map.of();
        nextGeneration();
    }

    public List<PhotoRecord> photos() {
        return photos;
    }

    public int size() {
        return photos.size();
    }

    public List<PhotoRecord> photosSlice(int offset, int bound) {
        return photos.subList(offset, Math.min(bound, size()));
    }

    public Map<Long, Integer> photoIndex() {
        return photoIndex;
    }

    public Integer indexById(long id) {
        return photoIndex.get(id);
    }
}