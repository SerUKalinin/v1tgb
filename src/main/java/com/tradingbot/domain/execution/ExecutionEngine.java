package com.tradingbot.domain.execution;

import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;

/**
 * Движок исполнения ордеров.
 * <p>
 * Отвечает за выполнение торговых ордеров через внешние биржевые адаптеры
 * и получение актуального статуса исполнения.
 * Является ключевым компонентом execution pipeline.
 */
public interface ExecutionEngine {

    /**
     * Исполняет предварительно валидированный и одобренный ордер.
     *
     * @param order доменная модель ордера, готовая к исполнению
     * @return результат исполнения ордера (статус, идентификаторы, ошибки)
     */
    ExecutionResult execute(Order order);

    /**
     * Проверяет актуальный статус ордера на стороне биржи.
     *
     * @param clientOrderId идентификатор ордера в системе
     * @return доменный результат статуса исполнения
     */
    ExecutionResult verifyOrder(String clientOrderId);
}