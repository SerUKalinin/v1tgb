package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Результат исполнения торгового ордера.
 */
@Value
@Builder
@AllArgsConstructor
public class ExecutionResult {
    String orderId;
    String clientOrderId;
    String exchangeOrderId;
    String exchangeTradeId;
    String symbol;
    OrderSide side;
    BigDecimal executedQty;
    BigDecimal executedPrice;
    BigDecimal feeAmount;
    String feeAsset;
    boolean success;
    String errorMessage;
    @Builder.Default
    Instant executedAt = Instant.now();

    public static ExecutionResult success(
            String orderId,
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
                .success(true)
                .build();
    }
    public static ExecutionResult failure(String orderId, String errorMessage) {
        return ExecutionResult.builder()
                .orderId(orderId)
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}