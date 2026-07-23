package com.github.curiousoddman.curious_images.ui.controller.services;

import com.github.curiousoddman.curious_images.model.GridCellData;
import com.github.curiousoddman.curious_images.model.Media;
import com.github.curiousoddman.curious_images.util.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class PhotoGridModel {
    private final AtomicLong generation = new AtomicLong();

    private List<GridCellData> cells     = List.of();
    private Map<Long, Integer> cellIndex = Map.of();

    public long nextGeneration() {
        return generation.incrementAndGet();
    }

    public long generation() {
        return generation.get();
    }

    public void setCells(List<GridCellData> cells) {
        this.cells = List.copyOf(cells);
        this.cellIndex = CollectionUtils.getIdToIndexMap(cells, r -> r.photo()
                                                                      .getId());
    }

    public void clear() {
        cells = List.of();
        cellIndex = Map.of();
        nextGeneration();
    }

    public List<GridCellData> cells() {
        return cells.stream()
                    .toList();
    }

    public int size() {
        return cells.size();
    }

    public List<GridCellData> photosSlice(int offset, int bound) {
        return cells.subList(offset, Math.min(bound, size()));
    }

    public Integer indexById(long id) {
        return cellIndex.get(id);
    }

    public List<Media> media() {
        return cells.stream()
                    .map(GridCellData::media)
                    .toList();
    }
}