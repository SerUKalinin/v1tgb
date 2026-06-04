package com.tradingbot.tracing;

import org.slf4j.MDC;
import java.util.UUID;

/**
 * Утилита для управления MDC (Mapped Diagnostic Context) на основе разделенной модели контекста.
 * Обеспечивает сквозное структурированное логирование всех идентификаторов.
 */
public final class ExecutionLogContext {
    
    public static final String AGGREGATE_ID = "aggregate_id";
    public static final String CORRELATION_ID = "correlation_id";
    public static final String SIGNAL_ID = "signal_id";
    public static final String ORDER_ID = "order_id";
    public static final String EXECUTION_ID = "execution_id";
    public static final String CAUSATION_ID = "causation_id";

    private ExecutionLogContext() {}

    /**
     * Заполняет MDC данными из единого контекста исполнения.
     */
    public static void load(ExecutionContext context) {
        if (context == null) return;
        
        IdentityContext identity = context.identity();
        ExecutionAttemptContext attempt = context.attempt();
        BusinessContext business = context.business();

        if (identity != null) {
            put(AGGREGATE_ID, identity.aggregateId());
            put(CORRELATION_ID, identity.correlationId());
            put(SIGNAL_ID, identity.signalId());
        }
        if (attempt != null) {
            put(EXECUTION_ID, attempt.executionId());
            put(CAUSATION_ID, attempt.causationId());
        }
        if (business != null && business.orderId() != null && !"UNKNOWN".equals(business.orderId())) {
            MDC.put(ORDER_ID, business.orderId());
        }
    }
    /**
     * Очищает MDC от идентификаторов выполнения.
     */
    public static void clear() {
        MDC.remove(AGGREGATE_ID);
        MDC.remove(CORRELATION_ID);
        MDC.remove(SIGNAL_ID);
        MDC.remove(ORDER_ID);
        MDC.remove(EXECUTION_ID);
        MDC.remove(CAUSATION_ID);
    }

    private static void put(String key, UUID value) {
        if (value != null) {
            MDC.put(key, value.toString());
        } else {
            MDC.remove(key);
        }
    }
}
