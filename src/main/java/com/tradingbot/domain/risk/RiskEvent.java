package com.tradingbot.domain.risk;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Базовый контракт доменных событий риск-менеджмента.
 *
 * <p>
 * Используется для реактивного обновления состояния RiskEngine
 * на основе событий торговой системы.
 */
public interface RiskEvent {

    /**
     * Временная метка события.
     */
    Instant getTimestamp();

    /**
     * Уникальный идентификатор события.
     */
    String getEventId();

    /**
     * Торговый символ, если применимо.
     */
    default String getSymbol() {
        return null;
    }

    /**
     * Событие исполнения сделки.
     */
    record TradeExecuted(
            String eventId,
            String symbol,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal realizedPnl,
            Instant timestamp
    ) implements RiskEvent {

        @Override
        public Instant getTimestamp() {
            return timestamp;
        }

        @Override
        public String getEventId() {
            return eventId;
        }

        @Override
        public String getSymbol() {
            return symbol;
        }
    }

    /**
     * Событие обновления рыночной цены.
     */
    record PriceUpdated(
            String eventId,
            String symbol,
            BigDecimal newPrice,
            Instant timestamp
    ) implements RiskEvent {

        @Override
        public Instant getTimestamp() {
            return timestamp;
        }

        @Override
        public String getEventId() {
            return eventId;
        }

        @Override
        public String getSymbol() {
            return symbol;
        }
    }

    /**
     * Событие остановки торгов.
     */
    record TradingHalted(
            String eventId,
            String reason,
            Instant timestamp
    ) implements RiskEvent {

        @Override
        public Instant getTimestamp() {
            return timestamp;
        }

        @Override
        public String getEventId() {
            return eventId;
        }
    }

    /**
     * Событие резервирования quote capital под BUY.
     */
    record CapitalReserved(
            String eventId,
            java.util.UUID orderId,
            BigDecimal amount,
            Instant timestamp
    ) implements RiskEvent {

        public CapitalReserved(
                String eventId,
                java.util.UUID orderId,
                BigDecimal amount
        ) {
            this(
                    eventId,
                    orderId,
                    amount,
                    Instant.now()
            );
        }

        @Override
        public Instant getTimestamp() {
            return timestamp;
        }

        @Override
        public String getEventId() {
            return eventId;
        }
    }

    /**
     * Событие освобождения ранее зарезервированного капитала.
     */
    record CapitalReleased(
            String eventId,
            java.util.UUID orderId,
            BigDecimal amount,
            String reason,
            Instant timestamp
    ) implements RiskEvent {

        public CapitalReleased(
                String eventId,
                java.util.UUID orderId,
                BigDecimal amount,
                String reason
        ) {
            this(
                    eventId,
                    orderId,
                    amount,
                    reason,
                    Instant.now()
            );
        }

        @Override
        public Instant getTimestamp() {
            return timestamp;
        }

        @Override
        public String getEventId() {
            return eventId;
        }
    }

    /**
     * Событие использования зарезервированного капитала BUY.
     *
     * <p>
     * Удаляет reservation и не возвращает amount
     * в available balance.
     */
    record CapitalConsumed(
            String eventId,
            java.util.UUID orderId,
            BigDecimal amount,
            String reason,
            Instant timestamp
    ) implements RiskEvent {

        public CapitalConsumed(
                String eventId,
                java.util.UUID orderId,
                BigDecimal amount,
                String reason
        ) {
            this(
                    eventId,
                    orderId,
                    amount,
                    reason,
                    Instant.now()
            );
        }

        @Override
        public Instant getTimestamp() {
            return timestamp;
        }

        @Override
        public String getEventId() {
            return eventId;
        }
    }

    /**
     * Событие зачисления quote capital после SELL.
     *
     * <p>
     * SELL не резервирует quote capital.
     * После фактического FILLED исполнение создаёт
     * CapitalCredited на сумму:
     *
     * <pre>
     * executedQuantity * executedPrice
     * </pre>
     */
    record CapitalCredited(
            String eventId,
            java.util.UUID orderId,
            BigDecimal amount,
            String reason,
            Instant timestamp
    ) implements RiskEvent {

        public CapitalCredited(
                String eventId,
                java.util.UUID orderId,
                BigDecimal amount,
                String reason
        ) {
            this(
                    eventId,
                    orderId,
                    amount,
                    reason,
                    Instant.now()
            );
        }

        @Override
        public Instant getTimestamp() {
            return timestamp;
        }

        @Override
        public String getEventId() {
            return eventId;
        }
    }
}