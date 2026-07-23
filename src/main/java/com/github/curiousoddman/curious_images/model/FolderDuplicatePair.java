package com.github.curiousoddman.curious_images.model;

import java.util.List;

/**
 * A pair of folders that share one or more duplicate groups, where every member of each such
 * group belongs to exactly one of these two folders. Groups touching a third folder (or confined
 * to a single folder) are excluded before this record is built — see
 * {@code FolderDuplicateGroupingService} — so every group in {@link #groups()} is unambiguously
 * "N photos in folder A, M photos in folder B", making "keep A / drop B" well-defined.
 * <p>
 * {@code folderAId} is always the smaller id, so a pair has one canonical identity regardless of
 * which folder came first in a given group's member list.
 *
 * @param folderAId   lower folder id of the pair
 * @param folderAPath immediate-parent directory path for folder A (derived from a member media's
 *                    absolute path, not a separate FOLDER-table lookup)
 * @param folderBId   higher folder id of the pair
 * @param folderBPath immediate-parent directory path for folder B
 * @param groups      every currently-unresolved duplicate group that spans exactly this pair
 */
public record FolderDuplicatePair(
        long folderAId,
        String folderAPath,
        long folderBId,
        String folderBPath,
        List<DuplicateGroup> groups
) {

    public int groupCount() {
        return groups.size();
    }

    public int photoCount(long folderId) {
        return (int) groups.stream()
                           .flatMap(g -> g.photos()
                                          .stream())
                           .filter(pwt -> folderId == pwt.media()
                                                         .getFolderId())
                           .count();
    }

    public long totalSize(long folderId) {
        return groups.stream()
                     .flatMap(g -> g.photos()
                                    .stream())
                     .filter(pwt -> folderId == pwt.media()
                                                   .getFolderId())
                     .mapToLong(pwt -> pwt.media()
                                          .getFileSize() == null ? 0L : pwt.media()
                                                                           .getFileSize())
                     .sum();
    }
}
