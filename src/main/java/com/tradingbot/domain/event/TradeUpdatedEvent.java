package com.tradingbot.domain.event;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.IdentityContext;
import com.tradingbot.tracing.IdentityFactory;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Фиксирует cumulative update существующего Trade
 * внутри одного execution lifecycle.
 *
 * Один executionId -> один Trade.
 *
 * Последующие cumulative checkpoints
 * передаются как delta для downstream projections.
 */
@Getter
public class TradeUpdatedEvent
        extends DomainEvent {

    private final UUID tradeId;
    private final UUID orderId;
    private final String symbol;
    private final String strategyId;

    /**
     * Новая порция quantity,
     * которую downstream должен применить.
     */
    private final BigDecimal deltaQuantity;

    /**
     * Полное cumulative quantity Trade.
     */
    private final BigDecimal cumulativeQuantity;

    /**
     * Полная cumulative average price.
     */
    private final BigDecimal cumulativePrice;

    /**
     * Effective price delta-исполнения.
     *
     * Нужен для корректного incremental average
     * в Position.
     */
    private final BigDecimal incrementalPrice;

    private final OrderSide side;
    private final String exchangeTradeId;
    private final String eventType;

    public TradeUpdatedEvent(
            IdentityContext identity,
            ExecutionAttemptContext attempt,
            BusinessContext business,
            String eventType,
            UUID tradeId,
            UUID orderId,
            String symbol,
            String strategyId,
            BigDecimal deltaQuantity,
            BigDecimal cumulativeQuantity,
            BigDecimal cumulativePrice,
            BigDecimal incrementalPrice,
            OrderSide side,
            String exchangeTradeId
    ) {

        super(
                identity,
                attempt,
                business,
                eventType,
                1
        );

        if (tradeId == null) {
            throw new IllegalArgumentException(
                    "tradeId cannot be null"
            );
        }

        if (orderId == null) {
            throw new IllegalArgumentException(
                    "orderId cannot be null"
            );
        }

        if (deltaQuantity == null
                || deltaQuantity.signum() <= 0) {

            throw new IllegalArgumentException(
                    "deltaQuantity must be positive"
            );
        }

        if (cumulativeQuantity == null
                || cumulativeQuantity.signum() <= 0) {

            throw new IllegalArgumentException(
                    "cumulativeQuantity must be positive"
            );
        }

        if (cumulativePrice == null
                || cumulativePrice.signum() <= 0) {

            throw new IllegalArgumentException(
                    "cumulativePrice must be positive"
            );
        }

        if (incrementalPrice == null
                || incrementalPrice.signum() <= 0) {

            throw new IllegalArgumentException(
                    "incrementalPrice must be positive"
            );
        }

        this.eventType =
                Objects.requireNonNull(
                        eventType,
                        "eventType cannot be null"
                );

        this.tradeId = tradeId;
        this.orderId = orderId;
        this.symbol = symbol;
        this.strategyId = strategyId;
        this.deltaQuantity = deltaQuantity;
        this.cumulativeQuantity = cumulativeQuantity;
        this.cumulativePrice = cumulativePrice;
        this.incrementalPrice = incrementalPrice;
        this.side = side;
        this.exchangeTradeId = exchangeTradeId;
    }

    /**
     * Создаёт deterministic event type
     * для cumulative state.
     */
    public static String eventTypeFor(
            UUID executionId,
            BigDecimal cumulativeQuantity,
            BigDecimal cumulativePrice
    ) {

        String checkpointKey =
                normalize(
                        cumulativeQuantity
                )
                        + "@"
                        + normalize(
                        cumulativePrice
                );

        return IdentityFactory.deriveCheckpointEventType(
                executionId,
                "TRADE_UPDATED",
                checkpointKey
        );
    }

    public static boolean isEventType(
            String eventType
    ) {

        return eventType != null
                && eventType.startsWith(
                "TRADE_UPDATED:"
        );
    }

    private static String normalize(
            BigDecimal value
    ) {

        return value
                .stripTrailingZeros()
                .toPlainString();
    }

    @Override
    public String getEventType() {
        return eventType;
    }
}