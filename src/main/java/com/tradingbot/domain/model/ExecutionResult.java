package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Результат исполнения торгового ордера.
 */
@Value
@Builder
@AllArgsConstructor
public class ExecutionResult {
    public enum Status {
        SUCCESS,
        REJECTED,
        CANCELED,
        FAILED_IO,
        TIMEOUT
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

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public static ExecutionResult success(
            UUID orderId,
            String exchangeOrderId,
            String exchangeTradeId,
            String symbol,
            OrderSide side,
            BigDecimal executedQty,
            BigDecimal executedPrice,
            BigDecimal feeAmount,
            String feeAsset,
            String clientOrderId) {

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
                .status(Status.SUCCESS)
                .build();
    }

    public static ExecutionResult rejected(UUID orderId, String errorMessage) {
        return ExecutionResult.builder()
                .orderId(orderId)
                .status(Status.REJECTED)
                .errorMessage(errorMessage)
                .build();
    }

    public static ExecutionResult failedIo(UUID orderId, String errorMessage) {
        return ExecutionResult.builder()
                .orderId(orderId)
                .status(Status.FAILED_IO)
                .errorMessage(errorMessage)
                .build();
    }

    public static ExecutionResult timeout(UUID orderId) {
        return ExecutionResult.builder()
                .orderId(orderId)
                .status(Status.TIMEOUT)
                .errorMessage("TIMEOUT")
                .build();
    }

    public static ExecutionResult canceled(UUID orderId) {
        return ExecutionResult.builder()
                .orderId(orderId)
                .status(Status.CANCELED)
                .errorMessage("CANCELED")
                .build();
    }
    @Deprecated
    public static ExecutionResult failure(UUID orderId, String errorMessage) {
        return failedIo(orderId, errorMessage);
    }
}