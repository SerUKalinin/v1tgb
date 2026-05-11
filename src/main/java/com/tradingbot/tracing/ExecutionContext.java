package com.tradingbot.tracing;

import java.util.UUID;
import java.io.Serializable;
import java.util.Objects;

/**
 * <h1>ExecutionContext (SSOT Execution Identity)</h1>
 * 
 * <p>Единственный носитель идентичности выполнения в системе. 
 * Реализует детерминированную модель идентификации (Deterministic Identity Model).
 * 
 * <h2>ARCHITECTURAL DECISION: AGGREGATE ROOT</h2>
 * <p>Aggregate Root для всего execution pipeline — это <b>Signal UUID</b>.
 * Он зафиксирован как {@code aggregateId} и является неизменным алиасом {@code signalId}.
 * 
 * <h2>SEMANTICS</h2>
 * <ul>
 *   <li>{@code aggregateId}: (SSOT) Системный immutable root identity.</li>
 *   <li>{@code correlationId}: Сквозной ID бизнес-потока.</li>
 *   <li>{@code signalId}: ID исходного сигнала.</li>
 *   <li>{@code orderId}: ID созданного ордера. Null до момента создания.</li>
 *   <li>{@code executionId}: ID конкретной попытки исполнения (attempt). Null до начала исполнения.</li>
 *   <li>{@code causationId}: ID события (eventId), вызвавшего текущий шаг.</li>
 * </ul>
 * 
 * <h2>INVARIANTS</h2>
 * <ol>
 *   <li>{@code aggregateId == signalId} (ALWAYS, IMMUTABLE, FINAL, EXPLICIT)</li>
 *   <li>{@code correlationId == signalId}</li>
 *   <li>{@code aggregateId} никогда не может быть равен {@code orderId} или {@code executionId}</li>
 *   <li>{@code executionId} генерируется ТОЛЬКО при старте попытки исполнения</li>
 * </ol>
 */
public record ExecutionContext(
        UUID aggregateId,
        UUID correlationId,
        UUID signalId,
        UUID orderId,
        UUID executionId,
        UUID causationId
) implements Serializable {

    public ExecutionContext {
        Objects.requireNonNull(aggregateId, "aggregateId cannot be null");
        Objects.requireNonNull(correlationId, "correlationId cannot be null");
        Objects.requireNonNull(signalId, "signalId cannot be null");
        Objects.requireNonNull(causationId, "causationId cannot be null");
        
        if (!aggregateId.equals(signalId)) {
            throw new IllegalStateException("Invariant violation: aggregateId must equal signalId");
        }
        if (!correlationId.equals(signalId)) {
            throw new IllegalStateException("Invariant violation: correlationId must equal signalId");
        }
    }

    /**
     * Инициализация потока из входящего сигнала.
     * executionId НЕ генерируется на этом этапе (t=0).
     */
    public static ExecutionContext init(UUID signalId) {
        return new ExecutionContext(
                signalId, // aggregateId
                signalId, // correlationId
                signalId, // signalId
                null,     // orderId
                null,     // executionId (NOT YET STARTED)
                signalId  // causationId (начальное событие/сигнал)
        );
    }

    /**
     * Привязка созданного ордера к контексту.
     */
    public ExecutionContext attachOrder(UUID orderId, UUID eventId) {
        return new ExecutionContext(
                this.aggregateId,
                this.correlationId,
                this.signalId,
                Objects.requireNonNull(orderId),
                this.executionId,
                Objects.requireNonNull(eventId)
        );
    }

    /**
     * Начало новой попытки исполнения (fill attempt).
     * Генерирует НОВЫЙ executionId для каждой попытки.
     */
    public ExecutionContext startExecutionAttempt(UUID eventId) {
        return new ExecutionContext(
                this.aggregateId,
                this.correlationId,
                this.signalId,
                this.orderId,
                UUID.randomUUID(), // NEW executionId for attempt
                Objects.requireNonNull(eventId)
        );
    }

    /**
     * Переход к следующему шагу (causality chain).
     */
    public ExecutionContext nextStep(UUID eventId) {
        return new ExecutionContext(
                this.aggregateId,
                this.correlationId,
                this.signalId,
                this.orderId,
                this.executionId,
                Objects.requireNonNull(eventId)
        );
    }

    /**
     * Восстановление контекста из персистентного состояния.
     */
    public static ExecutionContext restore(UUID aggregateId, UUID correlationId, UUID signalId, UUID orderId, UUID executionId, UUID causationId) {
        return new ExecutionContext(
                aggregateId,
                correlationId,
                signalId,
                orderId,
                executionId,
                causationId
        );
    }
}