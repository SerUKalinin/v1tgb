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
 * Он фиксируется как {@code aggregateId} в момент входа сигнала в систему и остается 
 * неизменным (IMMUTABLE) на протяжении всего жизненного цикла, включая создание ордеров, 
 * попытки исполнения и ретраи.
 * 
 * <h2>SEMANTICS</h2>
 * <ul>
 *   <li>{@code aggregateId}: (SSOT) Всегда равен {@code signalId}. Неизменен.</li>
 *   <li>{@code correlationId}: Сквозной ID бизнес-потока. Равен {@code signalId}.</li>
 *   <li>{@code signalId}: ID исходного сигнала.</li>
 *   <li>{@code orderId}: ID созданного ордера. Null до момента создания.</li>
 *   <li>{@code executionId}: ID конкретной попытки исполнения. Меняется при ретраях.</li>
 *   <li>{@code causationId}: ID события, вызвавшего текущий шаг.</li>
 * </ul>
 * 
 * <h2>INVARIANTS</h2>
 * <ol>
 *   <li>{@code aggregateId == correlationId == signalId}</li>
 *   <li>{@code aggregateId} никогда не равен {@code orderId}</li>
 *   <li>{@code causationId} всегда указывает на непосредственного родителя</li>
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
        Objects.requireNonNull(aggregateId, "aggregateId (SSOT) cannot be null");
        Objects.requireNonNull(correlationId, "correlationId cannot be null");
        Objects.requireNonNull(signalId, "signalId cannot be null");
        Objects.requireNonNull(causationId, "causationId cannot be null");
        
        if (!aggregateId.equals(signalId)) {
            throw new IllegalStateException("Invariant violation: aggregateId must equal signalId");
        }
    }

    /**
     * Инициализация потока из входящего сигнала.
     */
    public static ExecutionContext init(UUID signalId) {
        return new ExecutionContext(
                signalId, // aggregateId
                signalId, // correlationId
                signalId, // signalId
                null,     // orderId
                null,     // executionId
                signalId  // causationId (начальное событие)
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
                Objects.requireNonNull(eventId) // causationId = ID события создания ордера
        );
    }

    /**
     * Начало новой попытки исполнения (fill attempt).
     */
    public ExecutionContext startExecutionAttempt(UUID executionId, UUID eventId) {
        return new ExecutionContext(
                this.aggregateId,
                this.correlationId,
                this.signalId,
                this.orderId,
                Objects.requireNonNull(executionId),
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
}