package com.tradingbot.domain.execution;

import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.risk.ApprovedOrder;

/**
 * Интерфейс движка исполнения ордеров.
 */
/**
 * Интерфейс исполнительного движка.
 * Реализации ДОЛЖНЫ обеспечивать идемпотентность исполнения на основе clientOrderId.
 */
import com.tradingbot.infrastructure.execution.binance.OrderStatusResponse;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.risk.ApprovedOrder;

public interface ExecutionEngine {
    ExecutionResult execute(ApprovedOrder approvedOrder);
    OrderStatusResponse verifyOrder(String clientOrderId);
}