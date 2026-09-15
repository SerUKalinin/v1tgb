package com.tradingbot.domain.risk;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RiskStateReducerTest {

    @Test
    void shouldCreditBalanceForCapitalCredited() {
        UUID orderId = UUID.randomUUID();

        RiskState initialState =
                RiskState.builder()
                        .balance(new BigDecimal("9900.00000000"))
                        .totalEquity(new BigDecimal("10000.00000000"))
                        .dailyPnl(BigDecimal.ZERO)
                        .maxEquity(new BigDecimal("10000.00000000"))
                        .activeReservations(Map.of())
                        .processedEventIds(Set.of())
                        .halted(false)
                        .version(1L)
                        .build();

        RiskEvent.CapitalCredited event =
                new RiskEvent.CapitalCredited(
                        UUID.randomUUID().toString(),
                        orderId,
                        new BigDecimal("99.35104"),
                        "SELL order fully filled"
                );

        RiskStateReducer reducer =
                new RiskStateReducer();

        RiskState result =
                reducer.reduce(
                        initialState,
                        event
                );

        assertThat(result.getBalance())
                .isEqualByComparingTo(
                        new BigDecimal("9999.35104")
                );

        assertThat(result.getActiveReservations())
                .isEmpty();

        assertThat(result.getProcessedEventIds())
                .contains(event.getEventId());

        assertThat(result.isHalted())
                .isFalse();
    }

    @Test
    void shouldNotChangeReservationsWhenCapitalCredited() {
        UUID orderId = UUID.randomUUID();

        UUID reservedOrderId = UUID.randomUUID();

        RiskState initialState =
                RiskState.builder()
                        .balance(new BigDecimal("9800"))
                        .totalEquity(new BigDecimal("10000"))
                        .dailyPnl(BigDecimal.ZERO)
                        .maxEquity(new BigDecimal("10000"))
                        .activeReservations(
                                Map.of(
                                        reservedOrderId,
                                        new BigDecimal("100")
                                )
                        )
                        .processedEventIds(Set.of())
                        .halted(false)
                        .version(1L)
                        .build();

        RiskEvent.CapitalCredited event =
                new RiskEvent.CapitalCredited(
                        UUID.randomUUID().toString(),
                        orderId,
                        new BigDecimal("50"),
                        "SELL order fully filled"
                );

        RiskStateReducer reducer =
                new RiskStateReducer();

        RiskState result =
                reducer.reduce(
                        initialState,
                        event
                );

        assertThat(result.getBalance())
                .isEqualByComparingTo(
                        new BigDecimal("9850")
                );

        assertThat(result.getActiveReservations())
                .containsEntry(
                        reservedOrderId,
                        new BigDecimal("100")
                );

        assertThat(result.getProcessedEventIds())
                .contains(event.getEventId());
    }

    @Test
    void shouldIgnoreDuplicatedCapitalCreditedEvent() {
        UUID orderId = UUID.randomUUID();

        String eventId =
                UUID.randomUUID().toString();

        RiskState initialState =
                RiskState.builder()
                        .balance(new BigDecimal("9900"))
                        .totalEquity(new BigDecimal("10000"))
                        .dailyPnl(BigDecimal.ZERO)
                        .maxEquity(new BigDecimal("10000"))
                        .activeReservations(Map.of())
                        .processedEventIds(
                                Set.of(eventId)
                        )
                        .halted(false)
                        .version(1L)
                        .build();

        RiskEvent.CapitalCredited event =
                new RiskEvent.CapitalCredited(
                        eventId,
                        orderId,
                        new BigDecimal("100"),
                        "SELL order fully filled"
                );

        RiskStateReducer reducer =
                new RiskStateReducer();

        RiskState result =
                reducer.reduce(
                        initialState,
                        event
                );

        assertThat(result.getBalance())
                .isEqualByComparingTo(
                        new BigDecimal("9900")
                );

        assertThat(result.getProcessedEventIds())
                .containsExactly(eventId);
    }

    @Test
    void shouldHaltWhenCapitalCreditedAmountIsInvalid() {
        UUID orderId = UUID.randomUUID();

        RiskState initialState =
                RiskState.builder()
                        .balance(new BigDecimal("9900"))
                        .totalEquity(new BigDecimal("10000"))
                        .dailyPnl(BigDecimal.ZERO)
                        .maxEquity(new BigDecimal("10000"))
                        .activeReservations(Map.of())
                        .processedEventIds(Set.of())
                        .halted(false)
                        .version(1L)
                        .build();

        RiskEvent.CapitalCredited event =
                new RiskEvent.CapitalCredited(
                        UUID.randomUUID().toString(),
                        orderId,
                        BigDecimal.ZERO,
                        "SELL order fully filled"
                );

        RiskStateReducer reducer =
                new RiskStateReducer();

        RiskState result =
                reducer.reduce(
                        initialState,
                        event
                );

        assertThat(result.isHalted())
                .isTrue();

        assertThat(result.getBalance())
                .isEqualByComparingTo(
                        new BigDecimal("9900")
                );
    }
}