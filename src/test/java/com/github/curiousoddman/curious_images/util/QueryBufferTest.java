package com.github.curiousoddman.curious_images.util;

import org.jooq.Batch;
import org.jooq.Configuration;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.TransactionalRunnable;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class QueryBufferTest {

    private DSLContext transactionDsl;
    private DSLContext batchDsl;
    private Batch      batch;

    private QueryBuffer queryBuffer;

    @BeforeEach
    void setUp() {
        transactionDsl = mock(DSLContext.class);
        batchDsl = mock(DSLContext.class);
        batch = mock(Batch.class);

        queryBuffer = new QueryBuffer(transactionDsl);
    }

    @Test
    void add_shouldNotFlushBeforeBatchSize() {
        Query query = mock(Query.class);

        for (int i = 0; i < QueryBuffer.DB_FLUSH_BATCH_SIZE - 1; i++) {
            queryBuffer.add(query);
        }

        verify(transactionDsl, never()).transaction(any(TransactionalRunnable.class));
    }

    @Test
    void add_shouldFlushWhenBatchSizeReached() {
        Query query = mock(Query.class);

        Configuration configuration = mock(Configuration.class);

        doAnswer(invocation -> {
            TransactionalRunnable runnable = invocation.getArgument(0);
            runnable.run(configuration);
            return null;
        }).when(transactionDsl)
          .transaction(any(TransactionalRunnable.class));

        when(batchDsl.batch(anyList())).thenReturn(batch);

        try (MockedStatic<DSL> dsl = mockStatic(DSL.class)) {
            dsl.when(() -> DSL.using(configuration))
               .thenReturn(batchDsl);

            for (int i = 0; i < QueryBuffer.DB_FLUSH_BATCH_SIZE; i++) {
                queryBuffer.add(query);
            }

            verify(batchDsl).batch(anyList());
            verify(batch).execute();
        }
    }

    @Test
    void flush_shouldDoNothingWhenBufferEmpty() {
        queryBuffer.flush();

        verify(transactionDsl, never()).transaction(any(TransactionalRunnable.class));
    }

    @Test
    void flush_shouldExecuteBufferedQueries() {
        Query q1 = mock(Query.class);
        Query q2 = mock(Query.class);

        queryBuffer.add(q1, q2);

        Configuration configuration = mock(Configuration.class);

        doAnswer(invocation -> {
            TransactionalRunnable runnable = invocation.getArgument(0);
            runnable.run(configuration);
            return null;
        }).when(transactionDsl)
          .transaction(any(TransactionalRunnable.class));

        when(batchDsl.batch(anyList())).thenReturn(batch);

        try (MockedStatic<DSL> dsl = mockStatic(DSL.class)) {
            dsl.when(() -> DSL.using(configuration))
               .thenReturn(batchDsl);

            queryBuffer.flush();

            verify(batchDsl).batch(anyList());
            verify(batch).execute();
        }
    }

    @Test
    void close_shouldFlush() {
        Query query = mock(Query.class);
        queryBuffer.add(query);

        Configuration configuration = mock(Configuration.class);

        doAnswer(invocation -> {
            TransactionalRunnable runnable = invocation.getArgument(0);
            runnable.run(configuration);
            return null;
        }).when(transactionDsl)
          .transaction(any(TransactionalRunnable.class));

        when(batchDsl.batch(anyList())).thenReturn(batch);

        try (MockedStatic<DSL> dsl = mockStatic(DSL.class)) {
            dsl.when(() -> DSL.using(configuration))
               .thenReturn(batchDsl);

            queryBuffer.close();

            verify(batch).execute();
        }
    }

    @Test
    void bufferShouldBeClearedAfterFlush() {
        Query query = mock(Query.class);

        Configuration configuration = mock(Configuration.class);

        doAnswer(invocation -> {
            TransactionalRunnable runnable = invocation.getArgument(0);
            runnable.run(configuration);
            return null;
        }).when(transactionDsl)
          .transaction(any(TransactionalRunnable.class));

        when(batchDsl.batch(anyList())).thenReturn(batch);

        try (MockedStatic<DSL> dsl = mockStatic(DSL.class)) {
            dsl.when(() -> DSL.using(configuration))
               .thenReturn(batchDsl);

            queryBuffer.add(query);
            queryBuffer.flush();
            queryBuffer.flush();

            verify(transactionDsl, times(1)).transaction(any(TransactionalRunnable.class));
            verify(batch, times(1)).execute();
        }
    }
}