package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.domain.dedupe.PhotoHashRepository;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

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

    /** Must be called within the same transaction/configuration as {@link #deleteGroupsNotInJob}. */
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
}
