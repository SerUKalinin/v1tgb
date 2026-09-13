package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Результат исполнения торгового ордера.
 * <p>
 * Представляет итог выполнения ордера на бирже, включая статус исполнения,
 * фактически исполненные объёмы, цену, комиссии и идентификаторы на стороне биржи.
 * <p>
 * Используется как единая доменная модель результата execution pipeline.
 *
 * <p>Инварианты:
 * <ul>
 *   <li>FILLED → executedQty != null && executedPrice != null</li>
 *   <li>PARTIALLY_FILLED → executedQty != null && executedPrice != null</li>
 * </ul>
 */
@Value
@Builder(access = AccessLevel.PRIVATE)
public class ExecutionResult {

    public enum Status {
        FILLED,
        PARTIALLY_FILLED,
        ACCEPTED,
        REJECTED,
        CANCELED,
        EXCHANGE_STATE_UNKNOWN
    }

    UUID orderId;
    String clientOrderId;
    String exchangeOrderId;
    String exchangeTradeId;
    String symbol;
    OrderSide side;
    BigDecimal executedQty;
    BigDecimal executedPrice;
    BigDecimal feeAmount;
    String feeAsset;
    Status status;
    String errorMessage;

    @Builder.Default
    Instant executedAt = Instant.now();

    /**
     * Кастомный конструктор с проверкой доменных инвариантов.
     */
    private ExecutionResult(
            UUID orderId, String clientOrderId, String exchangeOrderId,
            String exchangeTradeId, String symbol, OrderSide side,
            BigDecimal executedQty, BigDecimal executedPrice,
            BigDecimal feeAmount, String feeAsset, Status status,
            String errorMessage, Instant executedAt) {

        validate(status, executedQty, executedPrice);

        this.orderId = orderId;
        this.clientOrderId = clientOrderId;
        this.exchangeOrderId = exchangeOrderId;
        this.exchangeTradeId = exchangeTradeId;
        this.symbol = symbol;
        this.side = side;
        this.executedQty = executedQty;
        this.executedPrice = executedPrice;
        this.feeAmount = feeAmount;
        this.feeAsset = feeAsset;
        this.status = status;
        this.errorMessage = errorMessage;
        this.executedAt = executedAt;
    }

    /**
     * Проверка доменных инвариантов результата исполнения.
     */
    private static void validate(Status status, BigDecimal executedQty, BigDecimal executedPrice) {
        if (status == null) return;

        boolean needsFillData =
                status == Status.FILLED || status == Status.PARTIALLY_FILLED;

        if (needsFillData && (executedQty == null || executedPrice == null)) {
            throw new IllegalArgumentException(
                    status + " requires non-null executedQty and executedPrice. " +
                            "Use static factory methods filled() or partiallyFilled()."
            );
        }
    }

    /**
     * Проверяет, полностью ли исполнен ордер.
     */
    public boolean isFilled() {
        return status == Status.FILLED;
    }

    // ───────────────────────────── Фабрики ─────────────────────────────

    /**
     * Полностью исполненный ордер.
     */
    public static ExecutionResult filled(
            UUID orderId, String exchangeOrderId, String exchangeTradeId,
            String symbol, OrderSide side,
            BigDecimal executedQty, BigDecimal executedPrice,
            BigDecimal feeAmount, String feeAsset, String clientOrderId) {

        return ExecutionResult.builder()
                .orderId(orderId)
                .clientOrderId(clientOrderId)
                .exchangeOrderId(exchangeOrderId)
                .exchangeTradeId(exchangeTradeId)
                .symbol(symbol)
                .side(side)
                .executedQty(executedQty)
                .executedPrice(executedPrice)
                .feeAmount(feeAmount)
                .feeAsset(feeAsset)
                .status(Status.FILLED)
                .build();
    }

    /**
     * Частично исполненный ордер.
     */
    public static ExecutionResult partiallyFilled(
            UUID orderId, String exchangeOrderId, String symbol, OrderSide side,
            BigDecimal executedQty, BigDecimal executedPrice, String clientOrderId) {

        return ExecutionResult.builder()
                .orderId(orderId)
                .clientOrderId(clientOrderId)
                .exchangeOrderId(exchangeOrderId)
                .symbol(symbol)
                .side(side)
                .executedQty(executedQty)
                .executedPrice(executedPrice)
                .status(Status.PARTIALLY_FILLED)
                .build();
    }

    /**
     * Ордер принят биржей в обработку.
     */
    public static ExecutionResult accepted(String exchangeOrderId, String clientOrderId) {
        return ExecutionResult.builder()
                .exchangeOrderId(exchangeOrderId)
                .clientOrderId(clientOrderId)
                .status(Status.ACCEPTED)
                .build();
    }

    /**
     * Универсальная фабрика для адаптеров биржи.
     */
    public static ExecutionResult of(
            UUID orderId, String exchangeOrderId,
            BigDecimal executedQty, BigDecimal executedPrice,
            Status status, String errorMessage) {

        return ExecutionResult.builder()
                .orderId(orderId)
                .exchangeOrderId(exchangeOrderId)
                .executedQty(executedQty)
                .executedPrice(executedPrice)
                .status(status)
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * Неопределённое состояние биржи.
     */
    public static ExecutionResult exchangeStateUnknown(UUID orderId) {
        return ExecutionResult.builder()
                .orderId(orderId)
                .status(Status.EXCHANGE_STATE_UNKNOWN)
                .errorMessage("EXCHANGE_STATE_UNKNOWN")
                .build();
    }

    /**
     * Ордер отклонён.
     */
    public static ExecutionResult rejected(UUID orderId, String errorMessage) {
        return ExecutionResult.builder()
                .orderId(orderId)
                .status(Status.REJECTED)
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * Ошибка взаимодействия с биржей.
     */
    public static ExecutionResult failedIo(UUID orderId, String errorMessage) {
        return ExecutionResult.builder()
                .orderId(orderId)
                .status(Status.EXCHANGE_STATE_UNKNOWN)
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * Ордер отменён.
     */
    public static ExecutionResult canceled(UUID orderId) {
        return ExecutionResult.builder()
                .orderId(orderId)
                .status(Status.CANCELED)
                .errorMessage("CANCELED")
                .build();
    }

    @Deprecated
    public static ExecutionResult failure(UUID orderId, String errorMessage) {
        return exchangeStateUnknown(orderId);
    }
}