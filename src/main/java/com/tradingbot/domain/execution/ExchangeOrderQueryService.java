package com.tradingbot.domain.execution;

import com.tradingbot.domain.model.ExecutionResult;

import java.math.BigDecimal;

/**
 * Сервис запросов к бирже для получения информации о состоянии ордеров и аккаунта.
 * <p>
 * Используется в execution pipeline для обеспечения идемпотентности,
 * проверки факта исполнения ордера и получения актуальных данных
 * по статусу и балансу.
 */
public interface ExchangeOrderQueryService {

    /**
     * Проверяет, был ли ордер уже исполнен на стороне биржи.
     *
     * @param clientOrderId идентификатор ордера в системе
     * @return true, если ордер уже исполнен, иначе false
     */
    boolean isOrderAlreadyExecuted(String clientOrderId);

    /**
     * Получает доступный баланс указанного актива.
     *
     * @param asset код актива (например, BTC, USDT)
     * @return доступный баланс
     */
    BigDecimal getAvailableBalance(String asset);

    /**
     * Получает текущий статус ордера с биржи.
     *
     * @param clientOrderId идентификатор ордера в системе
     * @return результат исполнения/состояния ордера
     */
    ExecutionResult getOrderStatus(String clientOrderId);
}