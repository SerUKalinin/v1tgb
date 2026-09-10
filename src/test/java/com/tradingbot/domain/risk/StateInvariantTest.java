package com.tradingbot.domain.risk;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.domain.position.PositionReducer;
import com.tradingbot.domain.position.PositionState;
import com.tradingbot.domain.position.PositionStatus;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.IdentityContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StateInvariantTest {

    @Test
    void shouldMaintainEquityAndPositionConsistency() {

        // 1. Risk state — баланс меньше totalEquity, чтобы инвариант не нарушался после убытка
        RiskState riskState = RiskState.builder()
                .totalEquity(new BigDecimal("10000"))
                .balance(new BigDecimal("5000"))
                .dailyPnl(BigDecimal.ZERO)
                .maxEquity(new BigDecimal("10000"))
                .symbolExposures(Map.of("BTCUSDT", BigDecimal.ZERO))
                .build();

        UUID signalId = UUID.randomUUID();
        BigDecimal qty = new BigDecimal("0.1");
        BigDecimal price = new BigDecimal("60000");

        // 2. PositionState
        PositionState positionState = new PositionState(
                "BTCUSDT",
                "strat-1",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                UUID.randomUUID(),
                BigDecimal.ZERO,
                null,
                null,
                PositionStatus.NEW,
                null,
                Instant.now()
        );

        // 3. TradeCreatedEvent — полная сигнатура с контекстом
        TradeCreatedEvent event = new TradeCreatedEvent(
                IdentityContext.of(signalId),
                ExecutionAttemptContext.firstAttempt(signalId),
                BusinessContext.of(UUID.randomUUID().toString()),
                UUID.randomUUID(),        // tradeId
                UUID.randomUUID(),        // orderId
                "BTCUSDT",
                "strat-1",
                qty,
                price,
                OrderSide.BUY,
                BigDecimal.ZERO,          // stopLoss
                BigDecimal.ZERO           // takeProfit
        );

        // 4. Reduce
        PositionReducer reducer = new PositionReducer();
        PositionState nextState = reducer.reduce(positionState, event);

        // 5. Проверки
        assertEquals(0, qty.compareTo(nextState.netQuantity()));
        assertEquals(0, price.compareTo(nextState.averagePrice()));

        // 6. Unrealized PnL
        BigDecimal newPrice = new BigDecimal("50000");
        BigDecimal unrealized = newPrice.subtract(price).multiply(qty);

        BigDecimal equity = riskState.getTotalEquity().add(unrealized);

        assertEquals(0, new BigDecimal("9000").compareTo(equity));

        // 7. Risk halt — убыток 2000 на equity 10000 = 20% > 5%, должен вызвать halt
        RiskStateReducer riskReducer = new RiskStateReducer();

        RiskEvent.TradeExecuted hugeLoss = new RiskEvent.TradeExecuted(
                "evt-1",
                "BTCUSDT",
                qty,
                price,
                new BigDecimal("-2000"),
                Instant.now()
        );

        RiskState halted = riskReducer.reduce(riskState, hugeLoss);

        assertTrue(halted.isHalted(), "State must be halted if loss > 5%");
    }
}
