package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.DuplicateGroupMember;
import com.github.curiousoddman.curious_images.dbobj.tables.records.DuplicateGroupMemberRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ThumbnailRecord;
import com.github.curiousoddman.curious_images.model.DuplicateGroup;
import com.github.curiousoddman.curious_images.model.Media;
import com.github.curiousoddman.curious_images.model.MediaWithThumbnail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.InsertValuesStep2;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.curiousoddman.curious_images.dbobj.Tables.MEDIA;
import static com.github.curiousoddman.curious_images.dbobj.Tables.PHOTO;
import static com.github.curiousoddman.curious_images.dbobj.Tables.THUMBNAIL;
import static com.github.curiousoddman.curious_images.dbobj.tables.DuplicateGroup.DUPLICATE_GROUP;
import static com.github.curiousoddman.curious_images.dbobj.tables.DuplicateGroupMember.DUPLICATE_GROUP_MEMBER;

/**
 * See the note in {@link MediaHashRepository} — written without sight of existing repository
 * conventions. Requires the DUPLICATE_GROUP / DUPLICATE_GROUP_MEMBER jOOQ classes, generated from
 * migration V004.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class DuplicateGroupRepository {
    private final DSLContext      dsl;
    private final MediaRepository mediaRepository;

    public long insertGroup(DSLContext ctx, long jobId, String extension, String pixelHash, LocalDateTime now) {
        return ctx.insertInto(DUPLICATE_GROUP)
                  .set(DUPLICATE_GROUP.DUPLICATE_JOB_ID, jobId)
                  .set(DUPLICATE_GROUP.EXTENSION, extension)
                  .set(DUPLICATE_GROUP.CONTENT_HASH, pixelHash)
                  .set(DUPLICATE_GROUP.CREATED_AT, now)
                  .returningResult(DUPLICATE_GROUP.ID)
                  .fetchOne(DUPLICATE_GROUP.ID);
    }

    public void insertMembers(DSLContext ctx, long groupId, List<Long> mediaIds) {
        InsertValuesStep2<DuplicateGroupMemberRecord, Long, Long> step = ctx.insertInto(DUPLICATE_GROUP_MEMBER,
                DUPLICATE_GROUP_MEMBER.DUPLICATE_GROUP_ID, DUPLICATE_GROUP_MEMBER.MEDIA_ID);
        for (Long photoId : mediaIds) {
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
        Result<Record> rows = dsl.select(DUPLICATE_GROUP.ID, DUPLICATE_GROUP.EXTENSION, DUPLICATE_GROUP.CONTENT_HASH)
                                 .select(MEDIA.fields())
                                 .select(THUMBNAIL.fields())
                                 .from(DUPLICATE_GROUP)
                                 .join(DUPLICATE_GROUP_MEMBER)
                                 .on(DUPLICATE_GROUP_MEMBER.DUPLICATE_GROUP_ID.eq(DUPLICATE_GROUP.ID))
                                 .join(MEDIA)
                                 .on(MEDIA.ID.eq(DUPLICATE_GROUP_MEMBER.MEDIA_ID))
                                 .leftJoin(THUMBNAIL)
                                 .on(THUMBNAIL.MEDIA_ID.eq(MEDIA.ID))
                                 .where(DUPLICATE_GROUP.ACCEPTED.isNull()
                                                                .or(DUPLICATE_GROUP.ACCEPTED.eq(false)))
                                 .orderBy(DUPLICATE_GROUP.ID, MEDIA.FILENAME)
                                 .fetch();

        Map<Long, GroupAccumulator> byGroupId = new LinkedHashMap<>();
        for (Record r : rows) {
            long groupId = r.get(DUPLICATE_GROUP.ID);
            GroupAccumulator acc = byGroupId.computeIfAbsent(groupId, id -> new GroupAccumulator(
                    id, r.get(DUPLICATE_GROUP.EXTENSION), r.get(DUPLICATE_GROUP.CONTENT_HASH)));
            Long            mediaId   = r.get(MEDIA.ID);
            ThumbnailRecord thumbnail = r.get(THUMBNAIL.MEDIA_ID) == null ? null : r.into(THUMBNAIL);
            Media           media     = mediaRepository.findMediaById(mediaId);
            acc.photos.add(new MediaWithThumbnail(media, thumbnail));
        }

        return byGroupId.values()
                        .stream()
                        .map(acc -> new DuplicateGroup(acc.groupId, acc.extension, acc.pixelHash, acc.photos))
                        .toList();
    }

    /**
     * Removes one media from one group, used right after that media's DB row has been deleted.
     * Run inside the same transaction as the {@code PHOTO}/{@code THUMBNAIL} deletes — see
     * {@code DuplicateResolutionService}.
     */
    public void deleteMember(DSLContext ctx, long groupId, long photoId) {
        ctx.deleteFrom(DUPLICATE_GROUP_MEMBER)
           .where(DUPLICATE_GROUP_MEMBER.DUPLICATE_GROUP_ID.eq(groupId)
                                                           .and(DUPLICATE_GROUP_MEMBER.MEDIA_ID.eq(photoId)))
           .execute();
    }

    public int countMembers(DSLContext ctx, long groupId) {
        return ctx.fetchCount(DUPLICATE_GROUP_MEMBER, DUPLICATE_GROUP_MEMBER.DUPLICATE_GROUP_ID.eq(groupId));
    }

    public void deleteGroupCascade(DSLContext ctx, long groupId) {
        ctx.deleteFrom(DUPLICATE_GROUP_MEMBER)
           .where(DUPLICATE_GROUP_MEMBER.DUPLICATE_GROUP_ID.eq(groupId))
           .execute();
        ctx.deleteFrom(DUPLICATE_GROUP)
           .where(DUPLICATE_GROUP.ID.eq(groupId))
           .execute();
    }

    public void markGroupResolved(long groupId) {
        log.info("Setting group as accepted {}", groupId);
        dsl.update(DUPLICATE_GROUP)
           .set(DUPLICATE_GROUP.ACCEPTED, true)
           .where(DUPLICATE_GROUP.ID.eq(groupId))
           .execute();
    }

    public Map<Long, Integer> countDuplicatesForPhotos(List<Long> ids) {
        DuplicateGroupMember m1 = DUPLICATE_GROUP_MEMBER.as("m1");
        DuplicateGroupMember m2 = DUPLICATE_GROUP_MEMBER.as("m2");

        return dsl.select(
                          m1.MEDIA_ID,
                          DSL.count(m2.MEDIA_ID)
                             .minus(1)
                             .as("duplicate_count")
                  )
                  .from(m1)
                  .join(m2)
                  .on(m1.DUPLICATE_GROUP_ID.eq(m2.DUPLICATE_GROUP_ID))
                  .where(m1.MEDIA_ID.in(ids))
                  .groupBy(m1.MEDIA_ID)
                  .fetchMap(
                          m1.MEDIA_ID,
                          r -> r.get("duplicate_count", Integer.class)
                  );
    }

    private static final class GroupAccumulator {
        private final long                     groupId;
        private final String                   extension;
        private final String                   pixelHash;
        private final List<MediaWithThumbnail> photos = new ArrayList<>();

        private GroupAccumulator(long groupId, String extension, String pixelHash) {
            this.groupId = groupId;
            this.extension = extension;
            this.pixelHash = pixelHash;
        }
    }
}
