package com.tradingbot.domain.execution;

import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.OrderRequest;

/**
 * Исполнительный движок для отправки торговых ордеров.
 */
public interface ExecutionEngine {

    /**
     * Выполняет торговый ордер.
     *
     * @param request запрос на исполнение ордера
     * @return результат исполнения
     */
    ExecutionResult execute(OrderRequest request);
}