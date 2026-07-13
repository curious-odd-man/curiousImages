package com.github.curiousoddman.curious_images.util;

import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.impl.DSL;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RequiredArgsConstructor
public class QueryBuffer implements AutoCloseable {
    public static final int DB_FLUSH_BATCH_SIZE = 50;

    private final DSLContext  dsl;
    private final List<Query> buffer = new ArrayList<>();

    public void add(Query... q) {
        buffer.addAll(Arrays.asList(q));
        if (buffer.size() >= DB_FLUSH_BATCH_SIZE) {
            flush();
        }
    }

    public void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        dsl.transaction(cfg -> DSL.using(cfg)
                                  .batch(buffer)
                                  .execute());
        buffer.clear();
    }

    @Override
    public void close() {
        flush();
    }
}
