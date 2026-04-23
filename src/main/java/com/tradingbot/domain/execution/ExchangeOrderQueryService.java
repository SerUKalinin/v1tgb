package com.tradingbot.domain.execution;

import java.util.UUID;

/**
 * Интерфейс для проверки статуса ордера на стороне биржи.
 * Используется для обеспечения идемпотентности при повторных попытках исполнения.
 */
import java.math.BigDecimal;

public interface ExchangeOrderQueryService {
    boolean isOrderAlreadyExecuted(String clientOrderId);
    BigDecimal getAvailableBalance(String asset);
}
