package com.tradingbot.application.service.execution;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.model.Order;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Полностью неизменяемый снимок состояния ордера.
 * <p>
 * Используется как DTO уровня исполнения (TransitionContext),
 * чтобы гарантировать отсутствие побочных эффектов и мутаций доменной модели
 * при обработке переходов состояния ордера.
 * <p>
 * Представляет собой снапшот ключевых параметров ордера в момент создания.
 */
@Getter
@Builder
public final class OrderSnapshot {

    private final UUID id;
    private final String clientOrderId;
    private final String symbol;
    private final OrderSide side;
    private final OrderType type;
    private final OrderStatus status;
    private final BigDecimal originalQuantity;
    private final BigDecimal price;
    private final String exchangeOrderId;
    private final BigDecimal executedQuantity;
    private final BigDecimal averagePrice;

    /**
     * Создаёт неизменяемый снимок состояния ордера из доменной модели.
     *
     * @param order доменный объект ордера
     * @return неизменяемый OrderSnapshot, отражающий текущее состояние ордера
     */
    public static OrderSnapshot from(Order order) {
        return OrderSnapshot.builder()
                .id(order.getId())
                .clientOrderId(order.getClientOrderId())
                .symbol(order.getSymbol())
                .side(order.getSide())
                .type(order.getType())
                .status(order.getStatus())
                .originalQuantity(order.getOriginalQuantity())
                .price(order.getPrice())
                .exchangeOrderId(order.getExchangeOrderId())
                .executedQuantity(order.getExecutedQuantity())
                .averagePrice(order.getAveragePrice())
                .build();
    }
}