package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.exception.InvalidOrderStateException;
import com.tradingbot.domain.policy.OrderStateTransitionPolicy;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.IdentityFactory;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Доменный агрегат: торговый ордер.
 *
 * <p>
 * Инкапсулирует жизненный цикл ордера от создания
 * до терминального состояния.
 *
 * <p>
 * Identity contract:
 * <ul>
 *     <li>orderId идентифицирует ордер;</li>
 *     <li>executionId идентифицирует ОДИН lifecycle исполнения этого ордера;</li>
 *     <li>executionId вычисляется один раз при создании Order;</li>
 *     <li>executionId неизменяем после создания;</li>
 *     <li>terminal state не очищает executionId.</li>
 * </ul>
 */
@Getter
public class Order {

    // ==================== Identity ====================

    private final UUID id;
    private final String clientOrderId;
    private final String symbol;
    private final String strategyId;
    private final UUID signalId;

    /**
     * Immutable execution lifecycle identity.
     *
     * <p>
     * Один Order -> один executionId -> один lifecycle.
     */
    private final UUID executionId;

    // ==================== Order parameters ====================

    private final OrderSide side;
    private final OrderType type;
    private final BigDecimal originalQuantity;
    private final BigDecimal price;

    // ==================== Lifecycle state ====================

    private OrderStatus status;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    // ==================== Execution data ====================

    private Instant executionStartedAt;
    private int executionAttempts = 0;

    private String exchangeOrderId;
    private BigDecimal executedQuantity;
    private BigDecimal averagePrice;
    private String rejectionReason;

    /**
     * Idempotency marker for the last applied execution result.
     *
     * <p>
     * Это технический marker применения результата,
     * а НЕ lifecycle identity.
     */
    private UUID lastAppliedExecutionId;

    // ==================== Constructor ====================

    /**
     * Приватный конструктор агрегата.
     *
     * <p>
     * Используется только фабричными методами:
     * {@link #createPendingExecution(UUID, String, String, OrderSide, OrderType,
     * BigDecimal, BigDecimal, String, UUID)}
     * и {@link #reconstruct(UUID, String, String, OrderSide, OrderType,
     * BigDecimal, BigDecimal, String, UUID, OrderStatus, long, Instant,
     * Instant, UUID, Instant, String, BigDecimal, BigDecimal, String, int, UUID)}.
     */
    private Order(
            UUID id,
            String clientOrderId,
            String symbol,
            OrderSide side,
            OrderType type,
            BigDecimal originalQuantity,
            BigDecimal price,
            String strategyId,
            UUID signalId,
            UUID executionId,
            OrderStatus status,
            long version
    ) {
        if (status == null) {
            throw new InvalidOrderStateException(
                    "Order status cannot be null"
            );
        }

        this.id = Objects.requireNonNull(
                id,
                "orderId is required"
        );

        this.clientOrderId = Objects.requireNonNull(
                clientOrderId,
                "clientOrderId is required"
        );

        this.symbol = Objects.requireNonNull(
                symbol,
                "symbol is required"
        );

        this.side = Objects.requireNonNull(
                side,
                "side is required"
        );

        this.type = Objects.requireNonNull(
                type,
                "type is required"
        );

        this.originalQuantity = Objects.requireNonNull(
                originalQuantity,
                "quantity is required"
        );

        this.price = price;

        this.strategyId = Objects.requireNonNull(
                strategyId,
                "strategyId is required"
        );

        this.signalId = Objects.requireNonNull(
                signalId,
                "signalId is required"
        );

        this.executionId = Objects.requireNonNull(
                executionId,
                "executionId is required"
        );

        this.status = status;
        this.version = version;
    }

    // ==================== Factory ====================

    /**
     * Создаёт новый Order в состоянии PENDING_EXECUTION.
     *
     * <p>
     * executionId создаётся детерминированно от orderId:
     *
     * <pre>
     * orderId
     *     ->
     * deriveExecution(orderId, 1)
     *     ->
     * executionId
     * </pre>
     */
    public static Order createPendingExecution(
            UUID id,
            String clientOrderId,
            String symbol,
            OrderSide side,
            OrderType type,
            BigDecimal originalQuantity,
            BigDecimal price,
            String strategyId,
            UUID signalId
    ) {
        Objects.requireNonNull(
                id,
                "orderId is required"
        );

        UUID executionId =
                IdentityFactory.deriveExecution(id, 1);

        return new Order(
                id,
                clientOrderId,
                symbol,
                side,
                type,
                originalQuantity,
                price,
                strategyId,
                signalId,
                executionId,
                OrderStatus.PENDING_EXECUTION,
                0L
        );
    }

    /**
     * Восстанавливает Order из persistence.
     *
     * <p>
     * Persisted executionId является authoritative identity.
     * При recovery новый executionId НЕ генерируется.
     */
    public static Order reconstruct(
            UUID id,
            String clientOrderId,
            String symbol,
            OrderSide side,
            OrderType type,
            BigDecimal originalQuantity,
            BigDecimal price,
            String strategyId,
            UUID signalId,
            OrderStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt,
            UUID executionId,
            Instant executionStartedAt,
            String exchangeOrderId,
            BigDecimal executedQuantity,
            BigDecimal averagePrice,
            String rejectionReason,
            int executionAttempts,
            UUID lastAppliedExecutionId
    ) {
        Order order = new Order(
                id,
                clientOrderId,
                symbol,
                side,
                type,
                originalQuantity,
                price,
                strategyId,
                signalId,
                Objects.requireNonNull(
                        executionId,
                        "Persisted order executionId cannot be null"
                ),
                status,
                version
        );

        order.createdAt = createdAt;
        order.updatedAt = updatedAt;
        order.executionStartedAt = executionStartedAt;
        order.executionAttempts = executionAttempts;
        order.exchangeOrderId = exchangeOrderId;
        order.executedQuantity = executedQuantity;
        order.averagePrice = averagePrice;
        order.rejectionReason = rejectionReason;
        order.lastAppliedExecutionId = lastAppliedExecutionId;

        return order;
    }

    // ==================== Identity ====================

    /**
     * Возвращает immutable lifecycle executionId.
     *
     * <p>
     * Этот ID нельзя менять или очищать.
     */
    public UUID getExecutionId() {
        return executionId;
    }

    // ==================== Execution lifecycle ====================

    /**
     * Переводит Order в EXECUTING.
     *
     * <p>
     * executionId из context должен совпадать
     * с immutable executionId агрегата.
     */
    public void markExecuting(
            ExecutionContext context
    ) {
        validateExecutionIdentity(context);

        if (this.status == OrderStatus.EXECUTING) {
            return;
        }

        OrderStateTransitionPolicy.validateAndPassThrough(
                context,
                this.status,
                OrderStatus.EXECUTING
        );

        this.status = OrderStatus.EXECUTING;
    }

    /**
     * Помечает Order как FILLED.
     */
    public void fill(
            ExecutionContext context,
            String exchangeOrderId,
            BigDecimal executedQty,
            BigDecimal executedPrice
    ) {
        validateExecutionIdentity(context);

        if (this.status == OrderStatus.FILLED) {
            return;
        }

        if (this.lastAppliedExecutionId != null
                && this.lastAppliedExecutionId.equals(
                context.attempt().executionId()
        )) {
            return;
        }

        OrderStateTransitionPolicy.validateAndPassThrough(
                context,
                this.status,
                OrderStatus.FILLED
        );

        this.lastAppliedExecutionId =
                context.attempt().executionId();

        this.exchangeOrderId = exchangeOrderId;
        this.executedQuantity = executedQty;
        this.averagePrice = executedPrice;
        this.status = OrderStatus.FILLED;
    }

    /**
     * Перегруженный fill без exchangeOrderId.
     */
    public void fill(
            ExecutionContext context,
            BigDecimal executedQty,
            BigDecimal executedPrice
    ) {
        fill(
                context,
                this.exchangeOrderId,
                executedQty,
                executedPrice
        );
    }

    /**
     * Принудительно переводит Order в FILLED
     * на основании authoritative reconciliation result.
     */
    public void forceFill(
            ExecutionContext context,
            String exchangeOrderId,
            BigDecimal executedQty,
            BigDecimal executedPrice
    ) {
        validateExecutionIdentity(context);

        if (this.status == OrderStatus.FILLED) {
            return;
        }

        OrderStateTransitionPolicy.validateAndPassThrough(
                context,
                this.status,
                OrderStatus.FILLED
        );

        this.lastAppliedExecutionId =
                context.attempt().executionId();

        this.exchangeOrderId = exchangeOrderId;
        this.executedQuantity = executedQty;
        this.averagePrice = executedPrice;
        this.status = OrderStatus.FILLED;
    }

    /**
     * Применяет partial fill.
     */
    public void applyPartialFill(
            ExecutionContext context,
            BigDecimal qty,
            BigDecimal price
    ) {
        validateExecutionIdentity(context);

        if (this.status == OrderStatus.PARTIALLY_FILLED) {
            return;
        }

        if (this.lastAppliedExecutionId != null
                && this.lastAppliedExecutionId.equals(
                context.attempt().executionId()
        )) {
            return;
        }

        OrderStateTransitionPolicy.validateAndPassThrough(
                context,
                this.status,
                OrderStatus.PARTIALLY_FILLED
        );

        this.lastAppliedExecutionId =
                context.attempt().executionId();

        this.executedQuantity = qty;
        this.averagePrice = price;
        this.status = OrderStatus.PARTIALLY_FILLED;
    }

    /**
     * Переводит Order в REJECTED.
     */
    public void markAsRejected(
            ExecutionContext context,
            String reason
    ) {
        validateExecutionIdentity(context);

        if (this.status == OrderStatus.REJECTED) {
            return;
        }

        OrderStateTransitionPolicy.validateAndPassThrough(
                context,
                this.status,
                OrderStatus.REJECTED
        );

        this.status = OrderStatus.REJECTED;
        this.rejectionReason = reason;
    }

    /**
     * Переводит Order в CANCELED.
     */
    public void markCancelled(
            ExecutionContext context
    ) {
        validateExecutionIdentity(context);

        if (this.status == OrderStatus.CANCELED) {
            return;
        }

        OrderStateTransitionPolicy.validateAndPassThrough(
                context,
                this.status,
                OrderStatus.CANCELED
        );

        this.status = OrderStatus.CANCELED;
    }

    /**
     * Переводит Order в UNKNOWN.
     *
     * <p>
     * UNKNOWN означает неопределённый результат выполнения.
     * Это НЕ rejection и НЕ cancellation.
     */
    public void markAsUnknown(
            ExecutionContext context
    ) {
        validateExecutionIdentity(context);

        if (this.status == OrderStatus.UNKNOWN) {
            return;
        }

        OrderStateTransitionPolicy.validateAndPassThrough(
                context,
                this.status,
                OrderStatus.UNKNOWN
        );

        this.status = OrderStatus.UNKNOWN;
    }

    /**
     * Переводит Order в RECOVERING.
     *
     * <p>
     * Метод оставлен для совместимости с текущим кодом.
     * State-machine policy должна отдельно определять,
     * разрешён ли этот переход.
     */
    public void markRecovering(
            ExecutionContext context
    ) {
        validateExecutionIdentity(context);

        if (this.status == OrderStatus.RECOVERING) {
            return;
        }

        OrderStateTransitionPolicy.validateAndPassThrough(
                context,
                this.status,
                OrderStatus.RECOVERING
        );

        this.status = OrderStatus.RECOVERING;
    }

    /**
     * Помечает Order как принятый биржей.
     *
     * <p>
     * Оставлено для совместимости текущей state machine.
     */
    public void markAccepted(
            ExecutionContext context,
            String exchangeOrderId
    ) {
        validateExecutionIdentity(context);

        if (this.status == OrderStatus.SENT_TO_EXCHANGE) {
            return;
        }

        OrderStateTransitionPolicy.validateAndPassThrough(
                context,
                this.status,
                OrderStatus.SENT_TO_EXCHANGE
        );

        this.exchangeOrderId = exchangeOrderId;
        this.status = OrderStatus.SENT_TO_EXCHANGE;
    }

    // ==================== Helpers ====================

    /**
     * Проверяет, что context относится к этому execution lifecycle.
     *
     * <p>
     * В отличие от старой реализации этот метод НИКОГДА
     * не присваивает executionId из context в Order.
     */
    private void validateExecutionIdentity(
            ExecutionContext context
    ) {
        Objects.requireNonNull(
                context,
                "ExecutionContext cannot be null"
        );

        UUID incomingExecutionId =
                Objects.requireNonNull(
                        context.attempt().executionId(),
                        "Context executionId cannot be null"
                );

        if (!this.executionId.equals(incomingExecutionId)) {
            throw new IllegalStateException(
                    String.format(
                            "Execution identity mismatch for order %s: " +
                                    "expected=%s, actual=%s",
                            this.id,
                            this.executionId,
                            incomingExecutionId
                    )
            );
        }
    }

    /**
     * Возвращает количество, оставшееся к исполнению.
     */
    public BigDecimal getRemainingQuantity() {
        BigDecimal executed =
                executedQuantity == null
                        ? BigDecimal.ZERO
                        : executedQuantity;

        return originalQuantity.subtract(executed);
    }

    /**
     * Возвращает исходное количество.
     */
    public BigDecimal getQuantity() {
        return originalQuantity;
    }

    /**
     * Оставлено для обратной совместимости.
     *
     * <p>
     * Фактически переходы должны контролироваться
     * OrderStateTransitionPolicy.
     */
    private void validateTransitionOrThrow(
            OrderStatus targetStatus
    ) {
        if (this.status == targetStatus) {
            return;
        }

        if (OrderStateTransitionPolicy.isTerminal(this.status)) {
            throw new IllegalStateException(
                    String.format(
                            "Cannot transition from terminal %s to %s",
                            this.status,
                            targetStatus
                    )
            );
        }

        if (!OrderStateTransitionPolicy.canTransition(
                this.status,
                targetStatus
        )) {
            throw new IllegalStateException(
                    String.format(
                            "Transition from %s to %s forbidden by policy",
                            this.status,
                            targetStatus
                    )
            );
        }
    }

    /**
     * Устанавливает exchangeOrderId.
     *
     * <p>
     * Это НЕ identity lifecycle и поэтому может изменяться
     * persistence/execution layer.
     */
    public void setExchangeOrderId(
            String exchangeOrderId
    ) {
        this.exchangeOrderId = exchangeOrderId;
    }
}