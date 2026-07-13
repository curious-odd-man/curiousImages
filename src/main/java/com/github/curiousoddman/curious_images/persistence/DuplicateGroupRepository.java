package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ThumbnailRecord;
import com.github.curiousoddman.curious_images.model.DuplicateGroup;
import com.github.curiousoddman.curious_images.model.PhotoWithThumbnail;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.curiousoddman.curious_images.dbobj.Tables.PHOTO;
import static com.github.curiousoddman.curious_images.dbobj.Tables.THUMBNAIL;
import static com.github.curiousoddman.curious_images.dbobj.tables.DuplicateGroup.DUPLICATE_GROUP;
import static com.github.curiousoddman.curious_images.dbobj.tables.DuplicateGroupMember.DUPLICATE_GROUP_MEMBER;

/**
 * See the note in {@link PhotoHashRepository} — written without sight of existing repository
 * conventions. Requires the DUPLICATE_GROUP / DUPLICATE_GROUP_MEMBER jOOQ classes, generated from
 * migration V004.
 */
@Repository
public class DuplicateGroupRepository {
    private final DSLContext dsl;

    public DuplicateGroupRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Must be called within the same transaction/configuration as {@link #deleteGroupsNotInJob}.
     */
    public long insertGroup(DSLContext ctx, long jobId, String extension, String pixelHash, LocalDateTime now) {
        return ctx.insertInto(DUPLICATE_GROUP)
                  .set(DUPLICATE_GROUP.DUPLICATE_JOB_ID, jobId)
                  .set(DUPLICATE_GROUP.EXTENSION, extension)
                  .set(DUPLICATE_GROUP.PIXEL_HASH, pixelHash)
                  .set(DUPLICATE_GROUP.CREATED_AT, now)
                  .returningResult(DUPLICATE_GROUP.ID)
                  .fetchOne(DUPLICATE_GROUP.ID);
    }

    public void insertMembers(DSLContext ctx, long groupId, List<Long> photoIds) {
        var step = ctx.insertInto(DUPLICATE_GROUP_MEMBER,
                DUPLICATE_GROUP_MEMBER.DUPLICATE_GROUP_ID, DUPLICATE_GROUP_MEMBER.PHOTO_ID);
        for (Long photoId : photoIds) {
            step = step.values(groupId, photoId);
        }
        step.execute();
    }

    /**
     * Deletes every DUPLICATE_GROUP (and its DUPLICATE_GROUP_MEMBER rows) that does NOT belong to
     * {@code currentJobId}. Called once the current run's groups have been inserted, so the
     * Duplicates View only ever sees the latest completed run's groups.
     * <p>
     * Members are deleted explicitly first since the migration doesn't declare
     * {@code ON DELETE CASCADE} on the FK (H2 default is RESTRICT).
     */
    public void deleteGroupsNotInJob(DSLContext ctx, long currentJobId) {
        ctx.deleteFrom(DUPLICATE_GROUP_MEMBER)
           .where(DUPLICATE_GROUP_MEMBER.DUPLICATE_GROUP_ID.in(
                   ctx.select(DUPLICATE_GROUP.ID)
                      .from(DUPLICATE_GROUP)
                      .where(DUPLICATE_GROUP.DUPLICATE_JOB_ID.ne(currentJobId))))
           .execute();
        ctx.deleteFrom(DUPLICATE_GROUP)
           .where(DUPLICATE_GROUP.DUPLICATE_JOB_ID.ne(currentJobId))
           .execute();
    }

    /**
     * Read side for the Duplicates tab: every current group with its member photos (+ thumbnail,
     * if generated). Since {@link #deleteGroupsNotInJob} guarantees only the latest completed
     * job's groups exist at any time, no job filter is needed here.
     */
    public List<DuplicateGroup> findAllGroupsWithMembers() {
        var rows = dsl.select(DUPLICATE_GROUP.ID, DUPLICATE_GROUP.EXTENSION, DUPLICATE_GROUP.PIXEL_HASH)
                      .select(PHOTO.fields())
                      .select(THUMBNAIL.fields())
                      .from(DUPLICATE_GROUP)
                      .join(DUPLICATE_GROUP_MEMBER)
                      .on(DUPLICATE_GROUP_MEMBER.DUPLICATE_GROUP_ID.eq(DUPLICATE_GROUP.ID))
                      .join(PHOTO)
                      .on(PHOTO.ID.eq(DUPLICATE_GROUP_MEMBER.PHOTO_ID))
                      .leftJoin(THUMBNAIL)
                      .on(THUMBNAIL.PHOTO_ID.eq(PHOTO.ID))
                      .orderBy(DUPLICATE_GROUP.ID, PHOTO.FILENAME)
                      .fetch();

        Map<Long, GroupAccumulator> byGroupId = new LinkedHashMap<>();
        for (var r : rows) {
            long groupId = r.get(DUPLICATE_GROUP.ID);
            GroupAccumulator acc = byGroupId.computeIfAbsent(groupId, id -> new GroupAccumulator(
                    id, r.get(DUPLICATE_GROUP.EXTENSION), r.get(DUPLICATE_GROUP.PIXEL_HASH)));
            PhotoRecord     photo     = r.into(PHOTO);
            ThumbnailRecord thumbnail = r.get(THUMBNAIL.PHOTO_ID) == null ? null : r.into(THUMBNAIL);
            acc.photos.add(new PhotoWithThumbnail(photo, thumbnail));
        }

        return byGroupId.values()
                        .stream()
                        .map(acc -> new DuplicateGroup(acc.groupId, acc.extension, acc.pixelHash, acc.photos))
                        .toList();
    }

    /**
     * Removes one photo from one group, used right after that photo's DB row has been deleted.
     * Run inside the same transaction as the {@code PHOTO}/{@code THUMBNAIL} deletes — see
     * {@code DuplicateResolutionService}.
     */
    public void deleteMember(DSLContext ctx, long groupId, long photoId) {
        ctx.deleteFrom(DUPLICATE_GROUP_MEMBER)
           .where(DUPLICATE_GROUP_MEMBER.DUPLICATE_GROUP_ID.eq(groupId)
                                                           .and(DUPLICATE_GROUP_MEMBER.PHOTO_ID.eq(photoId)))
           .execute();
    }

    /**
     * How many members a group has left — used to decide whether it should be cleaned up.
     */
    public int countMembers(DSLContext ctx, long groupId) {
        return ctx.fetchCount(DUPLICATE_GROUP_MEMBER, DUPLICATE_GROUP_MEMBER.DUPLICATE_GROUP_ID.eq(groupId));
    }

    /**
     * Removes a group that has fallen below 2 members after a deletion — it's no longer a
     * meaningful duplicate set. Members are deleted first, same RESTRICT-FK reasoning as
     * {@link #deleteGroupsNotInJob}.
     */
    public void deleteGroupCascade(DSLContext ctx, long groupId) {
        ctx.deleteFrom(DUPLICATE_GROUP_MEMBER)
           .where(DUPLICATE_GROUP_MEMBER.DUPLICATE_GROUP_ID.eq(groupId))
           .execute();
        ctx.deleteFrom(DUPLICATE_GROUP)
           .where(DUPLICATE_GROUP.ID.eq(groupId))
           .execute();
    }

    private static final class GroupAccumulator {
        private final long                     groupId;
        private final String                   extension;
        private final String                   pixelHash;
        private final List<PhotoWithThumbnail> photos = new ArrayList<>();

        private GroupAccumulator(long groupId, String extension, String pixelHash) {
            this.groupId = groupId;
            this.extension = extension;
            this.pixelHash = pixelHash;
        }
    }
}
