package com.github.curiousoddman.curious_images.ui.nodes;

/**
 * Discriminated payload carried by every {@link LibraryTreeNode}.
 * <p>
 * {@link FolderPayload}  — node maps to a {@code FOLDER} row; selecting it loads photos by folder.
 * {@link TimelinePayload} — node maps to a calendar slice; selecting it loads photos by date range.
 * {@link UndatedPayload}  — node maps to photos whose {@code capture_date} is NULL.
 */
public interface NodePayload {

    /**
     * Carries a {@code FOLDER.id}; used by IMPORT_ROOT and FOLDER nodes.
     */
    record FolderPayload(long folderId) implements NodePayload {
    }

    /**
     * Carries a calendar slice for timeline nodes.
     * <ul>
     *   <li>{@code year} non-null, {@code month} null  → TIMELINE_YEAR  (no grid)</li>
     *   <li>{@code year} + {@code month} non-null, {@code day} null → TIMELINE_MONTH</li>
     *   <li>All non-null → TIMELINE_DAY</li>
     * </ul>
     */
    record TimelinePayload(Integer year, Integer month, Integer day) implements NodePayload {
    }

    /**
     * Photos whose {@code capture_date} is NULL.
     */
    record UndatedPayload() implements NodePayload {
    }

    record AlbumPayload(long albumId) implements NodePayload {
    }

    record PersonPayload(long personId) implements NodePayload {
    }
}