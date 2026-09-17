package com.tradingbot.domain.risk;

import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.exchange.ExchangeFeasibilityPort;
import com.tradingbot.domain.exchange.FeasibilityRequest;
import com.tradingbot.domain.exchange.FeasibilityResult;
import com.tradingbot.domain.exchange.NormalizedOrder;
import com.tradingbot.domain.exchange.OrderNormalizationService;
import com.tradingbot.domain.position.PositionAvailabilityPort;
import com.tradingbot.infrastructure.outbox.OutboxService;
import com.tradingbot.tracing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты RiskService.
 *
 * <p>Проверяют:
 * <ul>
 *     <li>HALT блокирует risk events;</li>
 *     <li>обычное резервирование капитала;</li>
 *     <li>BUY резервирует quote capital;</li>
 *     <li>SELL не резервирует quote capital;</li>
 *     <li>ошибки RiskStatePort пробрасываются наружу.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RiskServiceTest {

    @Mock
    private RiskStatePort riskStatePort;

    @Mock
    private RiskStateReducer reducer;

    @Mock
    private RiskReservationLogPort riskReservationLogPort;

    @Mock
    private ExchangeFeasibilityPort feasibilityPort;

    @Mock
    private OrderNormalizationService normalizationService;

    @Mock
    private ExecutionLogger executionLogger;

    @Mock
    private OutboxService outboxService;

    @Mock
    private PositionAvailabilityPort positionAvailabilityPort;

    @InjectMocks
    private RiskService riskService;

    private RiskState createValidRiskState() {
        return RiskState.builder()
                .totalEquity(new BigDecimal("10000"))
                .balance(new BigDecimal("10000"))
                .halted(false)
                .version(0L)
                .build();
    }

    private ExecutionContext createContext(UUID orderId) {
        UUID signalId = UUID.randomUUID();

        return new ExecutionContext(
                IdentityContext.of(signalId),
                ExecutionAttemptContext.firstAttemptForOrder(orderId),
                BusinessContext.of(orderId.toString())
        );
    }

    private SignalEvent createSignal(
            SignalType type,
            BigDecimal price
    ) {
        return new SignalEvent(
                UUID.randomUUID(),
                "BTCUSDT",
                type,
                price,
                new BigDecimal("0.001"),
                null,
                null,
                Instant.now(),
                "SMA_STUB"
        );
    }

    private void mockNormalizeAndFeasibility(
            BigDecimal quantity,
            BigDecimal price
    ) {
        when(normalizationService.normalize(any(FeasibilityRequest.class)))
                .thenReturn(
                        new NormalizedOrder(
                                "BTCUSDT",
                                quantity,
                                price
                        )
                );

        when(feasibilityPort.check(any(FeasibilityRequest.class)))
                .thenReturn(FeasibilityResult.success());
    }

    @Test
    void shouldBlockEventsWhenHalted() {
        RiskState haltedState = createValidRiskState()
                .toBuilder()
                .halted(true)
                .build();

        when(riskStatePort.get())
                .thenReturn(haltedState);

        RiskEvent tradeEvent =
                new RiskEvent.TradeExecuted(
                        UUID.randomUUID().toString(),
                        "BTCUSDT",
                        BigDecimal.ONE,
                        BigDecimal.valueOf(50000),
                        BigDecimal.ZERO,
                        Instant.now()
                );

        riskService.publish(tradeEvent);

        verify(reducer, never())
                .reduce(any(), any());

        verify(riskStatePort, never())
                .save(any());

        verify(riskStatePort, never())
                .markEventProcessed(
                        any(),
                        any(),
                        any()
                );

        verify(riskReservationLogPort, never())
                .append(any());
    }

    @Test
    void shouldReserveCapital() {
        RiskState initialState =
                createValidRiskState();

        RiskState newState =
                initialState.toBuilder()
                        .version(1L)
                        .build();

        when(riskStatePort.get())
                .thenReturn(initialState);

        when(reducer.reduce(any(), any()))
                .thenReturn(newState);

        UUID orderId = UUID.randomUUID();

        BigDecimal amount =
                new BigDecimal("1000");

        RiskDecision decision =
                riskService.reserve(
                        createContext(orderId),
                        amount
                );

        assertThat(decision.isApproved())
                .isTrue();

        verify(riskStatePort)
                .markEventProcessed(
                        any(),
                        eq(newState),
                        any()
                );

        verify(riskReservationLogPort)
                .append(argThat(log ->
                        log.orderId().equals(orderId)
                                && log.amount().compareTo(amount) == 0
                                && log.eventType()
                                == RiskReservationEventType.RESERVE
                ));
    }

    @Test
    void shouldReserveCapitalForBuySignal() {
        RiskState initialState =
                createValidRiskState();

        RiskState newState =
                initialState.toBuilder()
                        .version(1L)
                        .build();

        when(riskStatePort.get())
                .thenReturn(initialState);

        when(reducer.reduce(
                any(RiskState.class),
                any(RiskEvent.class)
        )).thenReturn(newState);

        mockNormalizeAndFeasibility(
                new BigDecimal("0.001"),
                new BigDecimal("100")
        );

        SignalEvent signal =
                createSignal(
                        SignalType.BUY,
                        new BigDecimal("100")
                );

        ExecutionContext context =
                signal.getExecutionContext();

        Optional<com.tradingbot.domain.model.Order> result =
                riskService.evaluateSignal(
                        context,
                        signal
                );

        assertThat(result)
                .isPresent();

        assertThat(result.get().getSide())
                .isEqualTo(
                        com.tradingbot.common.enums.OrderSide.BUY
                );

        ArgumentCaptor<RiskEvent> eventCaptor =
                ArgumentCaptor.forClass(RiskEvent.class);

        verify(reducer)
                .reduce(
                        eq(initialState),
                        eventCaptor.capture()
                );

        RiskEvent event =
                eventCaptor.getValue();

        assertThat(event)
                .isInstanceOf(
                        RiskEvent.CapitalReserved.class
                );

        RiskEvent.CapitalReserved reserved =
                (RiskEvent.CapitalReserved) event;

        assertThat(reserved.amount())
                .isEqualByComparingTo(
                        new BigDecimal("0.1")
                );

        verify(riskStatePort)
                .markEventProcessed(
                        any(),
                        eq(newState),
                        eq(event)
                );

        verify(riskReservationLogPort)
                .append(argThat(log ->
                        log.eventType()
                                == RiskReservationEventType.RESERVE
                                && log.amount()
                                .compareTo(new BigDecimal("0.1")) == 0
                ));
    }

    @Test
    void shouldNotReserveCapitalForSellSignal() {
        RiskState initialState =
                createValidRiskState();

        when(riskStatePort.get())
                .thenReturn(initialState);

        when(positionAvailabilityPort.getAvailableQuantity(
                "BTCUSDT",
                "SMA_STUB"
        )).thenReturn(
                new BigDecimal("0.01")
        );

        mockNormalizeAndFeasibility(
                new BigDecimal("0.001"),
                new BigDecimal("100")
        );

        SignalEvent signal =
                createSignal(
                        SignalType.SELL,
                        new BigDecimal("100")
                );

        ExecutionContext context =
                signal.getExecutionContext();

        Optional<com.tradingbot.domain.model.Order> result =
                riskService.evaluateSignal(
                        context,
                        signal
                );

        assertThat(result)
                .isPresent();

        assertThat(result.get().getSide())
                .isEqualTo(
                        com.tradingbot.common.enums.OrderSide.SELL
                );

        verify(positionAvailabilityPort)
                .getAvailableQuantity(
                        "BTCUSDT",
                        "SMA_STUB"
                );

        /*
         * Критический инвариант:
         * SELL не должен проходить через RiskPolicy.canReserve()
         * и не должен создавать CapitalReserved.
         */
        verify(reducer, never())
                .reduce(
                        any(RiskState.class),
                        any(RiskEvent.CapitalReserved.class)
                );

        verify(riskStatePort, never())
                .markEventProcessed(
                        any(),
                        any(),
                        any(RiskEvent.CapitalReserved.class)
                );

        verify(riskReservationLogPort, never())
                .append(any());
    }

    @Test
    void shouldRejectSellWhenPositionIsInsufficient() {
        RiskState initialState =
                createValidRiskState();

        when(riskStatePort.get())
                .thenReturn(initialState);

        when(positionAvailabilityPort.getAvailableQuantity(
                "BTCUSDT",
                "SMA_STUB"
        )).thenReturn(
                new BigDecimal("0.0005")
        );

        when(normalizationService.normalize(any(FeasibilityRequest.class)))
                .thenReturn(
                        new NormalizedOrder(
                                "BTCUSDT",
                                new BigDecimal("0.001"),
                                new BigDecimal("100")
                        )
                );

        SignalEvent signal =
                createSignal(
                        SignalType.SELL,
                        new BigDecimal("100")
                );

        Optional<com.tradingbot.domain.model.Order> result =
                riskService.evaluateSignal(
                        signal.getExecutionContext(),
                        signal
                );

        assertThat(result)
                .isEmpty();

        verify(feasibilityPort, never())
                .check(any(FeasibilityRequest.class));

        verify(reducer, never())
                .reduce(any(), any());

        verify(riskStatePort, never())
                .markEventProcessed(
                        any(),
                        any(),
                        any()
                );

        verify(riskReservationLogPort, never())
                .append(any());
    }

    @Test
    void shouldThrowExceptionWhenPortFails() {
        when(riskStatePort.get())
                .thenThrow(
                        new RuntimeException("DB Error")
                );

        RiskEvent event =
                new RiskEvent.PriceUpdated(
                        UUID.randomUUID().toString(),
                        "BTCUSDT",
                        BigDecimal.ONE,
                        Instant.now()
                );

        assertThrows(
                RuntimeException.class,
                () -> riskService.publish(event)
        );

        verifyNoInteractions(reducer);
        verifyNoInteractions(riskReservationLogPort);
    }
}