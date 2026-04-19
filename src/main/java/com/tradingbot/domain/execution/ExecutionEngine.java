package com.tradingbot.domain.execution;

import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.risk.ApprovedOrder;
import com.tradingbot.common.enums.OrderStatus;

public interface ExecutionEngine {
    ExecutionResult execute(ApprovedOrder approvedOrder);
    boolean cancelOrder(String exchangeOrderId, String symbol);
    com.tradingbot.common.enums.OrderStatus getStatus(String exchangeOrderId, String symbol);
    com.tradingbot.common.enums.OrderStatus getStatusByClientOrderId(String clientOrderId, String symbol);
}