package com.tradingbot.domain.risk;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderType;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable object representing an order that has been approved by the Risk Engine.
 * This is the ONLY object that ExecutionEngine should accept.
 * It can only be created by the RiskEngine/RiskManager.
 */
@Getter
@Builder(toBuilder = true)
@ToString
public final class ApprovedOrder {
    private final String orderId;
    private final String clientOrderId;
    private final String symbol;
    private final OrderSide side;
    private final OrderType type;
    private final BigDecimal quantity;
    private final BigDecimal price;
    private final BigDecimal stopLoss;
    private final BigDecimal takeProfit;
    private final String strategyId;
    private final Instant approvedAt;
    private final long riskStateVersion;
    private final BigDecimal approvedExposure;

    // Private constructor for immutability
    private ApprovedOrder(String orderId, String clientOrderId, String symbol, OrderSide side, OrderType type,
                  BigDecimal quantity, BigDecimal price, BigDecimal stopLoss, BigDecimal takeProfit,
                  String strategyId, Instant approvedAt, long riskStateVersion, BigDecimal approvedExposure) {
        this.orderId = orderId;
        this.clientOrderId = clientOrderId;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.quantity = quantity;
        this.price = price;
        this.stopLoss = stopLoss;
        this.takeProfit = takeProfit;
        this.strategyId = strategyId;
        this.approvedAt = approvedAt;
        this.riskStateVersion = riskStateVersion;
        this.approvedExposure = approvedExposure;
    }
}
