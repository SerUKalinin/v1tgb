package com.tradingbot.domain.model;

import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.exception.InvalidOrderStateException;
import com.tradingbot.domain.policy.OrderStateTransitionPolicy;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Доменные агрегат: торговый ордер.
 * <p>
 * Инкапсулирует весь жизненный цикл ордера: от создания до исполнения,
 * включая управление состояниями, идемпотентность обработки и контроль
 * переходов состояний через state machine policy.
 */
@Getter
public class Order {
    // Идентификационные данные
    private final UUID id;
    private final String clientOrderId;
    private final String symbol;
    private final String strategyId;
    private final UUID signalId;

    // Параметры ордера
    private final OrderSide side;
    private final OrderType type;
    private final BigDecimal originalQuantity;
    private final BigDecimal price;

    // Состояние жизненного цикла
    private OrderStatus status;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    // Данные исполнения
    private UUID executionId;
    private Instant executionStartedAt;
    private int executionAttempts = 0;
    private String exchangeOrderId;
    private BigDecimal executedQuantity;
    private BigDecimal averagePrice;
    private String rejectionReason;
    private UUID lastAppliedExecutionId;

    /**
     * Приватный конструктор агрегата.
     * <p>
     * Используется только через фабричные методы createPendingExecution и reconstruct.
     * Обеспечивает контроль обязательных полей и базовую валидацию состояния.
     */
    private Order(UUID id,
                  String clientOrderId,
                  String symbol,
                  OrderSide side,
                  OrderType type,
                  BigDecimal originalQuantity,
                  BigDecimal price,
                  String strategyId,
                  UUID signalId,
                  OrderStatus status,
                  long version) {
        if (status == null) {
            throw new InvalidOrderStateException("Order status cannot be null");
        }

        this.id = Objects.requireNonNull(id, "orderId is required");
        this.clientOrderId = Objects.requireNonNull(clientOrderId, "clientOrderId is required");
        this.symbol = Objects.requireNonNull(symbol, "symbol is required");
        this.side = Objects.requireNonNull(side, "side is required");
        this.type = Objects.requireNonNull(type, "type is required");
        this.originalQuantity = Objects.requireNonNull(originalQuantity, "quantity is required");
        this.price = price;
        this.strategyId = Objects.requireNonNull(strategyId, "strategyId is required");
        this.signalId = Objects.requireNonNull(signalId, "signalId is required");

        this.status = status;
        this.version = version;
    }

    /**
     * Создает новый ордер в состоянии ожидания исполнения.
     *
     * @return новый агрегат Order в состоянии PENDING_EXECUTION
     */
    public static Order createPendingExecution(UUID id,
                                               String clientOrderId,
                                               String symbol,
                                               OrderSide side,
                                               OrderType type,
                                               BigDecimal originalQuantity,
                                               BigDecimal price,
                                               String strategyId,
                                               UUID signalId) {
        return new Order(id,
                clientOrderId,
                symbol,
                side,
                type,
                originalQuantity,
                price,
                strategyId,
                signalId,
                OrderStatus.PENDING_EXECUTION,
                0L);
    }

    /**
     * Восстанавливает агрегат из персистентного слоя.
     * <p>
     * Используется только при загрузке данных из БД (Persistence → Domain).
     */
    public static Order reconstruct(UUID id,
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
        Order order = new Order(id,
                clientOrderId,
                symbol,
                side,
                type,
                originalQuantity,
                price,
                strategyId,
                signalId,
                status,
                version);

        order.createdAt = createdAt;
        order.updatedAt = updatedAt;
        order.executionId = executionId;
        order.executionStartedAt = executionStartedAt;
        order.exchangeOrderId = exchangeOrderId;
        order.executedQuantity = executedQuantity;
        order.averagePrice = averagePrice;
        order.rejectionReason = rejectionReason;
        order.executionAttempts = executionAttempts;
        order.lastAppliedExecutionId = lastAppliedExecutionId;

        return order;
    }

    /**
     * Возвращает идентификатор execution, владеющий ордером.
     */
    public UUID getExecutionId() {
        return executionId;
    }

    /**
     * Назначает владельца исполнения ордера.
     * <p>
     * Используется для защиты от повторного захвата ордера разными execution-потоками.
     */
    public void assignExecutionOwner(UUID executionId) {
        Objects.requireNonNull(executionId, "executionId is required");

        // Idempotency guard: same owner — NOOP
        if (this.executionId != null && this.executionId.equals(executionId)) {
            return;
        }

        if (this.executionId != null && !OrderStateTransitionPolicy.isTerminal(this.status)) {
            throw new IllegalStateException(
                    String.format("Order %s already claimed by %s", this.id, this.executionId)
            );
        }

        this.executionId = executionId;
        this.executionStartedAt = Instant.now();
        this.executionAttempts++;
    }

    /**
     * Очищает владельца execution.
     * <p>
     * Допустимо только для терминальных состояний.
     */
    public void clearExecutionOwner() {
        if (!OrderStateTransitionPolicy.isTerminal(this.status)) {
            throw new IllegalStateException(
                    String.format("Order %s is not terminal (%s) - cannot clear execution owner", this.id, this.status)
            );
        }
        this.executionId = null;
    }

    /**
     * Переводит ордер в состояние EXECUTING.
     */
    public void markExecuting(ExecutionContext context) {
        UUID incomingExecutionId = context.attempt().executionId();

        if (this.status == OrderStatus.EXECUTING) {
            return;
        }

        OrderStateTransitionPolicy.validateAndPassThrough(context, this.status, OrderStatus.EXECUTING);
        this.executionId = incomingExecutionId;
        this.status = OrderStatus.EXECUTING;
    }

    /**
     * Помечает ордер как полностью исполненный (FILLED).
     */
    public void fill(ExecutionContext context, String exchangeOrderId, BigDecimal executedQty, BigDecimal executedPrice) {
        UUID incomingExecutionId = context.attempt().executionId();

        if (this.status == OrderStatus.FILLED) {
            return;
        }

        if (this.lastAppliedExecutionId != null && this.lastAppliedExecutionId.equals(incomingExecutionId)) {
            return;
        }

        OrderStateTransitionPolicy.validateAndPassThrough(context, this.status, OrderStatus.FILLED);

        this.lastAppliedExecutionId = incomingExecutionId;
        this.exchangeOrderId = exchangeOrderId;
        this.executedQuantity = executedQty;
        this.averagePrice = executedPrice;
        this.status = OrderStatus.FILLED;
    }

    /**
     * Перегруженный метод fill без exchangeOrderId.
     */
    public void fill(ExecutionContext context, BigDecimal executedQty, BigDecimal executedPrice) {
        fill(context, this.exchangeOrderId, executedQty, executedPrice);
    }

    /**
     * Принудительное исполнение ордера (используется в reconciliation).
     */
    public void forceFill(ExecutionContext context, String exchangeOrderId, BigDecimal executedQty, BigDecimal executedPrice) {
        if (this.status == OrderStatus.FILLED) {
            return;
        }

        OrderStateTransitionPolicy.validateAndPassThrough(context, this.status, OrderStatus.FILLED);

        this.lastAppliedExecutionId = context.attempt().executionId();
        this.exchangeOrderId = exchangeOrderId;
        this.executedQuantity = executedQty;
        this.averagePrice = executedPrice;
        this.status = OrderStatus.FILLED;
    }

    /**
     * Применяет частичное исполнение ордера.
     */
    public void applyPartialFill(ExecutionContext context, BigDecimal qty, BigDecimal price) {
        UUID incomingExecutionId = context.attempt().executionId();

        if (this.status == OrderStatus.PARTIALLY_FILLED) {
            return;
        }

        if (this.lastAppliedExecutionId != null && this.lastAppliedExecutionId.equals(incomingExecutionId)) {
            return;
        }

        OrderStateTransitionPolicy.validateAndPassThrough(context, this.status, OrderStatus.PARTIALLY_FILLED);

        this.lastAppliedExecutionId = incomingExecutionId;
        this.executedQuantity = qty;
        this.averagePrice = price;
        this.status = OrderStatus.PARTIALLY_FILLED;
    }

    /**
     * Переводит ордер в RECOVERING.
     */
    public void markRecovering(ExecutionContext context) {
        if (this.status == OrderStatus.RECOVERING) {
            return;
        }

        OrderStateTransitionPolicy.validateAndPassThrough(context, this.status, OrderStatus.RECOVERING);
        this.status = OrderStatus.RECOVERING;
    }

    /**
     * Помечает ордер как принятый биржей.
     */
    public void markAccepted(ExecutionContext context, String exchangeOrderId) {
        if (this.status == OrderStatus.SENT_TO_EXCHANGE) {
            return;
        }

        OrderStateTransitionPolicy.validateAndPassThrough(context, this.status, OrderStatus.SENT_TO_EXCHANGE);

        this.exchangeOrderId = exchangeOrderId;
        this.status = OrderStatus.SENT_TO_EXCHANGE;
    }

    /**
     * Помечает ордер как отклонённый.
     */
    public void markAsRejected(ExecutionContext context, String reason) {
        if (this.status == OrderStatus.REJECTED) {
            return;
        }

        OrderStateTransitionPolicy.validateAndPassThrough(context, this.status, OrderStatus.REJECTED);

        this.status = OrderStatus.REJECTED;
        this.rejectionReason = reason;
    }

    /**
     * Отменяет ордер.
     */
    public void markCancelled(ExecutionContext context) {
        if (this.status == OrderStatus.CANCELED) {
            return;
        }

        OrderStateTransitionPolicy.validateAndPassThrough(context, this.status, OrderStatus.CANCELED);
        this.status = OrderStatus.CANCELED;
    }

    /**
     * Помечает ордер как неизвестный.
     */
    public void markAsUnknown(ExecutionContext context) {
        if (this.status == OrderStatus.UNKNOWN) {
            return;
        }

        OrderStateTransitionPolicy.validateAndPassThrough(context, this.status, OrderStatus.UNKNOWN);
        this.status = OrderStatus.UNKNOWN;
    }

    /**
     * Возвращает оставшееся количество для исполнения.
     */
    public BigDecimal getRemainingQuantity() {
        BigDecimal executed = executedQuantity == null ? BigDecimal.ZERO : executedQuantity;
        return originalQuantity.subtract(executed);
    }

    /**
     * Возвращает исходное количество ордера.
     */
    public BigDecimal getQuantity() {
        return originalQuantity;
    }

    /**
     * Проверка перехода состояния (внутренняя логика state machine).
     */
    private void validateTransitionOrThrow(OrderStatus targetStatus) {
        if (this.status == targetStatus) {
            return;
        }

        if (OrderStateTransitionPolicy.isTerminal(this.status)) {
            throw new IllegalStateException(
                    String.format("Cannot transition from terminal %s to %s", this.status, targetStatus)
            );
        }

        if (!OrderStateTransitionPolicy.canTransition(this.status, targetStatus)) {
            throw new IllegalStateException(
                    String.format("Transition from %s to %s forbidden by policy", this.status, targetStatus)
            );
        }
    }

    /**
     * Устанавливает exchangeOrderId (используется адаптерами биржи).
     */
    public void setExchangeOrderId(String exchangeOrderId) {
        this.exchangeOrderId = exchangeOrderId;
    }
}