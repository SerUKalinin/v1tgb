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
public interface ExecutionEngine {

    /**
     * Выполняет торговый ордер.
     * 
     * @param approvedOrder ордер, одобренный риск-менеджером. 
     *                      Поле clientOrderId ДОЛЖНО использоваться как ключ идемпотентности.
     * @return результат исполнения. Повторные вызовы с тем же clientOrderId 
     *         ДОЛЖНЫ возвращать детерминированный результат.
     */
    ExecutionResult execute(ApprovedOrder approvedOrder);
}