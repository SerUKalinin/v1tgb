package com.tradingbot.domain.event;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.IdentityContext;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Доменное событие создания сделки.
 *
 * <p>Фиксирует факт создания trade-сущности в системе после обработки ордера.</p>
 *
 * <p>Используется для:
 * <ul>
 *     <li>учёта сделок</li>
 *     <li>аналитики PnL</li>
 *     <li>пост-обработки исполнения</li>
 * </ul>
 */
@Getter
public class TradeCreatedEvent extends DomainEvent {

    private final UUID tradeId;
    private final UUID orderId;
    private final String symbol;
    private final String strategyId;
    private final BigDecimal quantity;
    private final BigDecimal price;
    private final OrderSide side;
    private final BigDecimal stopLoss;
    private final BigDecimal takeProfit;

    /**
     * Создаёт событие создания сделки.
     *
     * @param identity контекст идентичности
     * @param attempt контекст попытки исполнения
     * @param business бизнес-контекст
     * @param tradeId идентификатор сделки
     * @param orderId идентификатор ордера
     * @param symbol торговый символ
     * @param strategyId идентификатор стратегии
     * @param quantity объём
     * @param price цена
     * @param side направление сделки
     * @param stopLoss уровень стоп-лосс
     * @param takeProfit уровень тейк-профит
     */
    public TradeCreatedEvent(
            IdentityContext identity,
            ExecutionAttemptContext attempt,
            BusinessContext business,
            UUID tradeId,
            UUID orderId,
            String symbol,
            String strategyId,
            BigDecimal quantity,
            BigDecimal price,
            OrderSide side,
            BigDecimal stopLoss,
            BigDecimal takeProfit
    ) {
        super(identity, attempt, business, 1);
        this.tradeId = tradeId;
        this.orderId = orderId;
        this.symbol = symbol;
        this.strategyId = strategyId;
        this.quantity = quantity;
        this.price = price;
        this.side = side;
        this.stopLoss = stopLoss;
        this.takeProfit = takeProfit;
    }

    /**
     * Тип события в системе.
     *
     * @return TRADE_CREATED
     */
    @Override
    public String getEventType() {
        return "TRADE_CREATED";
    }
}