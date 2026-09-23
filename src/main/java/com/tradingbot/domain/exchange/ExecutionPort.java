package com.tradingbot.domain.exchange;

import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Order;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Порт взаимодействия с биржей для исполнения торговых операций.
 *
 * <p>Определяет контракт выполнения торговых операций,
 * получения состояния ордера и актуальных балансов.</p>
 *
 * <p>Конкретная реализация биржи находится в infrastructure layer.</p>
 */
public interface ExecutionPort {

    /**
     * Размещает ордер на бирже.
     *
     * @param order доменная модель ордера
     * @return результат исполнения
     */
    ExecutionResult placeOrder(Order order);

    /**
     * Отменяет ранее размещённый ордер.
     *
     * @param clientOrderId идентификатор ордера в системе
     * @return результат отмены
     */
    ExecutionResult cancelOrder(String clientOrderId);

    /**
     * Получает актуальное состояние ордера непосредственно с биржи.
     *
     * <p>Для Binance необходимы оба значения:
     * symbol + clientOrderId.</p>
     *
     * @param symbol торговый символ
     * @param clientOrderId клиентский идентификатор ордера
     * @return актуальное состояние ордера
     */
    ExecutionResult getOrderStatus(
            String symbol,
            String clientOrderId
    );

    /**
     * Возвращает текущие балансы аккаунта на бирже.
     *
     * @return карта балансов
     */
    Map<String, BigDecimal> getBalances();
}