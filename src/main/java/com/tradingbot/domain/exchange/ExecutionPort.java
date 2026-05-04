package com.tradingbot.domain.exchange;

import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.risk.ApprovedOrder;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Порт для взаимодействия с внешними биржами.
 * Определяет контракт исполнения ордеров в терминах доменной области.
 */
public interface ExecutionPort {

    /**
     * Разместить новый ордер на бирже.
     * @param order Одобренный риск-движком ордер
     * @return Результат исполнения
     */
    ExecutionResult placeOrder(ApprovedOrder order);

    /**
     * Отменить существующий ордер.
     * @param clientOrderId Идентификатор ордера в нашей системе
     * @return Результат отмены
     */
    ExecutionResult cancelOrder(String clientOrderId);

    /**
     * Получить текущий статус ордера напрямую с биржи.
     * @param clientOrderId Идентификатор ордера в нашей системе
     * @return Результат запроса статуса
     */
    ExecutionResult getOrderStatus(String clientOrderId);

    /**
     * Получить балансы всех активов на аккаунте.
     * @return Карта: Код актива -> Доступный баланс
     */
    Map<String, BigDecimal> getBalances();
}
