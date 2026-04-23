package com.tradingbot.domain.execution;

import java.util.UUID;

/**
 * Интерфейс для проверки статуса ордера на стороне биржи.
 * Используется для обеспечения идемпотентности при повторных попытках исполнения.
 */
public interface ExchangeOrderQueryService {
    
    /**
     * Проверяет, был ли ордер уже исполнен или существует ли он на бирже.
     * 
     * @param clientOrderId уникальный идентификатор ордера в нашей системе
     * @return true, если ордер уже исполнен или принят биржей
     */
    boolean isOrderAlreadyExecuted(String clientOrderId);
}
