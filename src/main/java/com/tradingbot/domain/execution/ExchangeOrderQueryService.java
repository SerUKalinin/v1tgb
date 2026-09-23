package com.tradingbot.domain.execution;

import com.tradingbot.domain.model.ExecutionResult;

import java.math.BigDecimal;

/**
 * Сервис запросов к бирже для получения состояния ордеров
 * и актуальных данных аккаунта.
 */
public interface ExchangeOrderQueryService {

    /**
     * Проверяет, был ли ордер уже исполнен на стороне биржи.
     *
     * @param clientOrderId идентификатор ордера
     * @return true, если ордер уже исполнен
     */
    boolean isOrderAlreadyExecuted(String clientOrderId);

    /**
     * Получает доступный баланс указанного актива.
     *
     * @param asset код актива
     * @return доступный баланс
     */
    BigDecimal getAvailableBalance(String asset);

    /**
     * Получает текущее состояние ордера с биржи.
     *
     * @param symbol торговый символ
     * @param clientOrderId клиентский идентификатор
     * @return результат состояния ордера
     */
    ExecutionResult getOrderStatus(
            String symbol,
            String clientOrderId
    );
}