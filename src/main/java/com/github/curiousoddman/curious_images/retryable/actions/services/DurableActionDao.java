package com.github.curiousoddman.curious_images.retryable.actions.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PendingActionRecord;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.github.curiousoddman.curious_images.dbobj.Tables.PENDING_ACTION;

@Repository
@RequiredArgsConstructor
public class DurableActionDao {
    private final ObjectMapper mapper = new ObjectMapper();

    private final TimeProvider timeProvider;
    private final DSLContext   dsl;

    public List<PendingActionRecord> findExecutable() {
        return dsl.selectFrom(PENDING_ACTION)
                  .where(PENDING_ACTION.STATUS.eq("PENDING"))
                  .and(
                          PENDING_ACTION.NEXT_ATTEMPT_AT.isNull()
                                                        .or(PENDING_ACTION.NEXT_ATTEMPT_AT.le(timeProvider.now())))
                  .orderBy(PENDING_ACTION.ID.asc())
                  .fetch();
    }

    public void markInProgress(long id) {
        dsl.update(PENDING_ACTION)
           .set(PENDING_ACTION.STATUS, "IN_PROGRESS")
           .where(PENDING_ACTION.ID.eq(id))
           .execute();
    }

    public void markCompleted(long id) {
        dsl.update(PENDING_ACTION)
           .set(PENDING_ACTION.STATUS, "COMPLETED")
           .where(PENDING_ACTION.ID.eq(id))
           .execute();
    }

    public void markFailed(long id, String error) {
        dsl.update(PENDING_ACTION)
           .set(PENDING_ACTION.STATUS, "FAILED")
           .set(PENDING_ACTION.LAST_ERROR, error)
           .where(PENDING_ACTION.ID.eq(id))
           .execute();
    }

    public void retryLater(PendingActionRecord action, Exception e) {
        int retries = action.getRetryCount() + 1;

        dsl.update(PENDING_ACTION)
           .set(PENDING_ACTION.STATUS, "PENDING")
           .set(PENDING_ACTION.RETRY_COUNT, retries)
           .set(PENDING_ACTION.LAST_ERROR, e.getMessage())
           .where(PENDING_ACTION.ID.eq(action.getId()))
           .execute();
    }

    @SneakyThrows
    public <T> PendingActionRecord newAction(T payload) {
        return dsl.insertInto(PENDING_ACTION)
                  .set(PENDING_ACTION.TYPE, payload.getClass()
                                                   .getName())
                  .set(PENDING_ACTION.PAYLOAD, mapper.writeValueAsString(payload)
                                                     .getBytes())
                  .set(PENDING_ACTION.STATUS, "PENDING")
                  .set(PENDING_ACTION.RETRY_COUNT, 0)
                  .set(PENDING_ACTION.CREATED_AT, timeProvider.now())
                  .returning()
                  .fetchOne();
    }

    public void resetStuckActions() {
        dsl.update(PENDING_ACTION)
           .set(PENDING_ACTION.STATUS, "PENDING")
           .where(PENDING_ACTION.STATUS.eq("IN_PROGRESS"))
           .execute();
    }
}