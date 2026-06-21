package com.github.curiousoddman.curious_images.model;

import java.util.List;

/**
 * Read-side view of one {@code DUPLICATE_GROUP} row plus its member photos, assembled by
 * {@link com.github.curiousoddman.curious_images.persistence.DuplicateGroupRepository#findAllGroupsWithMembers()}
 * for display in the Duplicates tab. {@code DUPLICATE_GROUP} only ever holds the latest completed
 * job's groups (see {@code deleteGroupsNotInJob}), so this view needs no job filter.
 */
public record DuplicateGroupView(
        long groupId,
        String extension,
        String pixelHash,
        List<PhotoWithThumbnail> photos
) {
}
