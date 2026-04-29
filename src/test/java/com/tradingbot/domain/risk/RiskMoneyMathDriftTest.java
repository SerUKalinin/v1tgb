package com.tradingbot.domain.risk;

import com.tradingbot.common.util.MoneyMath;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.Map;
import java.util.Set;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

public class RiskMoneyMathDriftTest {

    private final RiskStateReducer reducer = new RiskStateReducer();

    @Test
    void shouldNotHaveDriftAfterManyOperations() {
        // Initial state with 10000.000000000000000000
        RiskState state = RiskState.builder()
                .balance(new BigDecimal("10000"))
                .totalEquity(new BigDecimal("10000"))
                .maxEquity(new BigDecimal("10000"))
                .dailyPnl(BigDecimal.ZERO)
                .reserved(BigDecimal.ZERO)
                .activeReservations(Map.of())
                .symbolExposures(Map.of())
                .processedEventIds(Set.of())
                .build();

        BigDecimal amount = new BigDecimal("123.456789012345678901"); // More than 18 digits
        UUID orderId = UUID.randomUUID();

        // 1. Reserve
        RiskEvent.CapitalReserved reserveEvent = new RiskEvent.CapitalReserved(
                UUID.randomUUID().toString(), orderId, amount);
        state = reducer.reduce(state, reserveEvent);

        // 2. Release (Compensation)
        RiskEvent.CapitalReleased releaseEvent = new RiskEvent.CapitalReleased(
                UUID.randomUUID().toString(), orderId, BigDecimal.ZERO, "COMPENSATION");
        state = reducer.reduce(state, releaseEvent);

        // After reserve and release of same amount, balance should be exactly 10000
        assertThat(state.getBalance().toPlainString()).isEqualTo("10000.000000000000000000");
        
        // 3. Perform 1000 small trades and check for drift
        BigDecimal profit = new BigDecimal("0.000000000000000001");
        for (int i = 0; i < 1000; i++) {
            RiskEvent.TradeExecuted trade = new RiskEvent.TradeExecuted(
                    UUID.randomUUID().toString(), "BTCUSDT", new BigDecimal("0.001"), 
                    new BigDecimal("50000"), profit, Instant.now());
            state = reducer.reduce(state, trade);
        }

        // 1000 * 1e-18 = 1e-15
        BigDecimal expectedPnl = new BigDecimal("0.000000000000001000");
        assertThat(state.getDailyPnl()).isEqualByComparingTo(expectedPnl);
        assertThat(state.getTotalEquity()).isEqualByComparingTo(new BigDecimal("10000.000000000000001000"));
    }
}
