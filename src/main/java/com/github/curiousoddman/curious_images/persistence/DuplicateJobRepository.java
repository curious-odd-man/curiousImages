package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.domain.dedupe.PhotoHashRepository;
import com.github.curiousoddman.curious_images.util.TextUtils;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

import static com.github.curiousoddman.curious_images.dbobj.tables.DuplicateJob.DUPLICATE_JOB;

/**
 * See the note in {@link PhotoHashRepository} — written without sight of existing repository
 * conventions. Requires the DUPLICATE_JOB jOOQ classes, generated from migration V004.
 */
@Repository
@RequiredArgsConstructor
public class DuplicateJobRepository {
    private final DSLContext dsl;

    public long insertRunning(LocalDateTime startedAt, int totalCount) {
        return dsl.insertInto(DUPLICATE_JOB)
                  .set(DUPLICATE_JOB.STATUS, "RUNNING")
                  .set(DUPLICATE_JOB.STARTED_AT, startedAt)
                  .set(DUPLICATE_JOB.TOTAL_COUNT, totalCount)
                  .set(DUPLICATE_JOB.PROCESSED_COUNT, 0)
                  .returningResult(DUPLICATE_JOB.ID)
                  .fetchOne(DUPLICATE_JOB.ID);
    }

    public void markCompleted(long jobId, LocalDateTime endedAt, int groupCount) {
        dsl.update(DUPLICATE_JOB)
           .set(DUPLICATE_JOB.STATUS, "COMPLETED")
           .set(DUPLICATE_JOB.ENDED_AT, endedAt)
           .set(DUPLICATE_JOB.GROUP_COUNT, groupCount)
           .where(DUPLICATE_JOB.ID.eq(jobId))
           .execute();
    }

    public void markInterrupted(long jobId, LocalDateTime endedAt) {
        dsl.update(DUPLICATE_JOB)
           .set(DUPLICATE_JOB.STATUS, "INTERRUPTED")
           .set(DUPLICATE_JOB.ENDED_AT, endedAt)
           .where(DUPLICATE_JOB.ID.eq(jobId))
           .execute();
    }

    public void markFailed(long jobId, LocalDateTime endedAt, String errorMessage) {
        dsl.update(DUPLICATE_JOB)
           .set(DUPLICATE_JOB.STATUS, "FAILED")
           .set(DUPLICATE_JOB.ENDED_AT, endedAt)
           .set(DUPLICATE_JOB.ERROR_MESSAGE, TextUtils.truncate(errorMessage, 2048))
           .where(DUPLICATE_JOB.ID.eq(jobId))
           .execute();
    }
}
