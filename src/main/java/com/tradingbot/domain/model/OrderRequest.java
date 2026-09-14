package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderType;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Запрос на создание торгового ордера.
 * <p>
 * Используется как входная доменная модель для формирования ордера
 * перед прохождением валидации, нормализации и execution pipeline.
 * Поддерживает совместимость через поле amount (legacy alias).
 */
@Value
@Builder(toBuilder = true)
public class OrderRequest {

    UUID orderId;

    String clientOrderId;

    String symbol;

    BigDecimal quantity;

    /**
     * Legacy-алиас для quantity.
     * Используется для обратной совместимости старых интеграций.
     */
    BigDecimal amount;

    OrderSide side;

    OrderType type;

    BigDecimal price;

    String strategyId;

    /**
     * Возвращает фактическое количество ордера.
     * <p>
     * Приоритет у quantity, fallback на amount для legacy совместимости.
     *
     * @return количество ордера
     */
    public BigDecimal getAmount() {
        return amount != null ? amount : quantity;
    }
}