package com.tradingbot.domain.execution;

import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;

/**
 * Интерфейс движка исполнения ордеров.
 * Определяет логику взаимодействия с внешними системами исполнения.
 */
public interface ExecutionEngine {

    /**
     * Исполнить одобренный ордер.
     * @param order Данные ордера
     * @return Результат исполнения
     */
    ExecutionResult execute(Order order);

    /**
     * Проверить статус ордера.
     * @param clientOrderId Идентификатор ордера
     * @return Результат запроса статуса в доменном формате
     */
    ExecutionResult verifyOrder(String clientOrderId);
}