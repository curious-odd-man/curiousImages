package com.github.curiousoddman.curious_images.ui.controller.services;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoTagRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.TagEmbeddingRecord;
import com.github.curiousoddman.curious_images.util.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class PhotoGridModel {
    private final AtomicLong generation = new AtomicLong();

    private List<PhotoRecord>                                  photos     = List.of();
    private Map<Long, Integer>                                 photoIndex = Map.of();
    private Map<Long, Map<PhotoTagRecord, TagEmbeddingRecord>> tags       = Map.of();

    public long nextGeneration() {
        return generation.incrementAndGet();
    }

    public long generation() {
        return generation.get();
    }

    public void setPhotos(List<PhotoRecord> photos, Map<Long, Map<PhotoTagRecord, TagEmbeddingRecord>> tags) {
        this.photos = List.copyOf(photos);
        this.photoIndex = CollectionUtils.getIdToIndexMap(photos);
        this.tags = Map.copyOf(tags);
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

    public Integer indexById(long id) {
        return photoIndex.get(id);
    }

    public Map<PhotoTagRecord, TagEmbeddingRecord> getPhotoTags(PhotoRecord photo) {
        Long id = photo.getId();
        return tags.get(id);
    }
}