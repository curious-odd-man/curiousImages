package com.github.curiousoddman.curious_images.retryable.actions.services;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PendingActionRecord;
import com.github.curiousoddman.curious_images.retryable.actions.handler.DurableActionsHandler;
import com.github.curiousoddman.curious_images.util.StartupRunnable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class DurableActionService implements StartupRunnable {
    private final DurableActionDao                      dao;
    private final Map<String, DurableActionsHandler<?>> handlers;

    public DurableActionService(DurableActionDao dao, List<DurableActionsHandler<?>> handlerList) {
        this.dao = dao;
        this.handlers = new HashMap<>();
        handlerList.forEach(h -> {
            log.info("Registering handler for type: {} from {}", h.getHandledType(), h.getClass());
            handlers.put(h.getHandledType(), h);
        });
    }

    @Override
    @Transactional
    public void onStartup() {
        log.info("Executing pending actions...");
        dao.resetStuckActions();
        List<PendingActionRecord> actions = dao.findExecutable();
        log.info("Found {} pending actions to execute.", actions.size());
        for (PendingActionRecord action : actions) {
            process(action);

        }
        log.info("Pending actions execution completed.");
    }

/*    public void updateLyrics(String newLyrics, Path filePath) {
        UpdateLyricsPayload payload = new UpdateLyricsPayload(newLyrics, filePath);
        PendingActionRecord action = dao.newAction(payload);
        process(action);
    }*/

    private void process(PendingActionRecord action) {
        DurableActionsHandler<?> handler = handlers.get(action.getType());
        if (handler == null) {
            dao.markFailed(action.getId(), "No handler for type");
            return;
        }

        try {
            dao.markInProgress(action.getId());
            handler.execute(action);
            dao.markCompleted(action.getId());
        } catch (Exception e) {
            dao.retryLater(action, e);
        }
    }
}