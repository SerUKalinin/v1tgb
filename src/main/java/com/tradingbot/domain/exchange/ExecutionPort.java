package com.tradingbot.domain.exchange;

import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.ExecutionResult;
import java.math.BigDecimal;
import java.util.Map;

public interface ExecutionPort {
    /**
     * Размещает ордер на бирже.
     */
    ExecutionResult placeOrder(Order order);


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
