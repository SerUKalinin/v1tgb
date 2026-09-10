package com.tradingbot.domain.event;

import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.IdentityContext;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Доменное событие исполнения ордера (полное заполнение).
 *
 * <p>Фиксирует факт полного исполнения ордера на бирже и содержит
 * внешние идентификаторы исполнения.</p>
 *
 * <p>Используется для:
 * <ul>
 *     <li>обновления состояния ордера</li>
 *     <li>outbox публикации</li>
 *     <li>reconciliation и аудита исполнения</li>
 * </ul>
 */
@Getter
public class OrderFilledEvent extends DomainEvent {

    private final UUID orderId;
    private final String externalExecutionId;
    private final String symbol;
    private final BigDecimal quantity;
    private final BigDecimal price;

    /**
     * Создаёт событие полного исполнения ордера.
     *
     * @param identity контекст идентичности исполнения
     * @param attempt контекст попытки исполнения
     * @param business бизнес-контекст
     * @param orderId идентификатор ордера
     * @param externalExecutionId идентификатор исполнения на бирже
     * @param symbol торговый символ
     * @param quantity исполненное количество
     * @param price цена исполнения
     */
    public OrderFilledEvent(
            IdentityContext identity,
            ExecutionAttemptContext attempt,
            BusinessContext business,
            UUID orderId,
            String externalExecutionId,
            String symbol,
            BigDecimal quantity,
            BigDecimal price
    ) {
        super(identity, attempt, business, 1);
        this.orderId = orderId;
        this.externalExecutionId = externalExecutionId;
        this.symbol = symbol;
        this.quantity = quantity;
        this.price = price;
    }

    /**
     * Тип события в системе.
     *
     * @return ORDER_FILLED
     */
    @Override
    public String getEventType() {
        return "ORDER_FILLED";
    }
}