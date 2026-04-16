package com.tradingbot.domain.execution;

import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.risk.ApprovedOrder;

/**
 * Интерфейс движка исполнения ордеров.
 */
public interface ExecutionEngine {

    /**
     * Исполняет ордер, одобренный риск-менеджером.
     * В Stage 3 это единственный входной контракт.
     */
    ExecutionResult execute(ApprovedOrder approvedOrder);
}