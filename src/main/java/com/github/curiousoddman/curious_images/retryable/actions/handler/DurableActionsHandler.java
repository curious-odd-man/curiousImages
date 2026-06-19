package com.github.curiousoddman.curious_images.retryable.actions.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PendingActionRecord;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

public abstract class DurableActionsHandler<T> {
    static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public String getHandledType() {
        Type superClass = getClass().getGenericSuperclass();
        return (((ParameterizedType) superClass).getActualTypeArguments()[0]).getTypeName();
    }

    public T deserialize(PendingActionRecord action, T... hidden) throws Exception {
        return (T) OBJECT_MAPPER.readValue(action.getPayload(), Class.forName(getHandledType()));
    }

    public abstract void execute(PendingActionRecord action) throws Exception;
}
