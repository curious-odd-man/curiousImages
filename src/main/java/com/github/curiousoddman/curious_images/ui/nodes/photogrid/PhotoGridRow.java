package com.github.curiousoddman.curious_images.ui.nodes.photogrid;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;

import java.util.List;

/**
 * One row's worth of photos for the virtualized photo grid — the item type of
 * {@code LibraryController}'s {@code ListView<PhotoGridRow>}. The row size is the current column
 * count (computed from viewport width and thumbnail size); the last row of a selection may have
 * fewer photos than that.
 */
public record PhotoGridRow(List<PhotoRecord> photos) {
}
