package com.tradingbot.domain.exchange;

import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.ExecutionResult;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Порт взаимодействия с биржей для исполнения торговых операций.
 * <p>
 * Определяет контракт выполнения ордеров, управления ими и получения
 * актуального состояния аккаунта на стороне биржи.
 * Реализации данного интерфейса инкапсулируют работу с конкретными
 * биржевыми API.
 */
public interface ExecutionPort {

    /**
     * Размещает ордер на бирже.
     *
     * @param order доменная модель ордера, содержащая параметры сделки
     * @return результат исполнения ордера (статус, идентификаторы, ошибки при наличии)
     */
    ExecutionResult placeOrder(Order order);

    /**
     * Отменяет ранее размещённый ордер.
     *
     * @param clientOrderId идентификатор ордера в нашей системе
     * @return результат операции отмены
     */
    ExecutionResult cancelOrder(String clientOrderId);

    /**
     * Получает текущий статус ордера напрямую с биржи.
     *
     * @param clientOrderId идентификатор ордера в нашей системе
     * @return актуальный статус исполнения ордера
     */
    ExecutionResult getOrderStatus(String clientOrderId);

    /**
     * Возвращает текущие балансы аккаунта на бирже.
     *
     * @return карта балансов: ключ — код актива, значение — доступный баланс
     */
    Map<String, BigDecimal> getBalances();
}