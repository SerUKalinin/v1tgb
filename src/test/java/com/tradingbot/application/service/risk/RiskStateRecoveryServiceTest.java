package com.tradingbot.application.service.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.policy.OrderStateTransitionPolicy;
import com.tradingbot.domain.risk.RiskEvent;
import com.tradingbot.domain.risk.RiskState;
import com.tradingbot.domain.risk.RiskStateRecoveryPort;
import com.tradingbot.domain.risk.RiskStateRecoveryPort.RiskEventRecord;
import com.tradingbot.domain.risk.RiskStateReducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskStateRecoveryServiceTest {

    @Mock
    private RiskStateRecoveryPort recoveryPort;

    @Mock
    private RiskEngine riskEngine;

    @Mock
    private RiskStateReducer reducer;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private RiskReconciler riskReconciler;

    @Mock
    private ExchangeOrderQueryService exchangeQueryService;

    @Mock
    private SystemStateManager stateManager;

    @Mock
    private OrderRepositoryPort orderRepositoryPort;

    private RiskStateRecoveryService recoveryService;

    private RiskState persistedState;

    private RiskState reconciledState;

    @BeforeEach
    void setUp() {

        recoveryService =
                new RiskStateRecoveryService(
                        recoveryPort,
                        riskEngine,
                        reducer,
                        objectMapper,
                        riskReconciler,
                        exchangeQueryService,
                        stateManager,
                        orderRepositoryPort
                );

        UUID reservedOrderId =
                UUID.randomUUID();

        persistedState =
                RiskState.builder()
                        .balance(
                                new BigDecimal(
                                        "9900.00000000"
                                )
                        )
                        .totalEquity(
                                new BigDecimal(
                                        "10000.00000000"
                                )
                        )
                        .dailyPnl(
                                new BigDecimal("12.50")
                        )
                        .maxEquity(
                                new BigDecimal(
                                        "10050.00000000"
                                )
                        )
                        .activeReservations(
                                Map.of(
                                        reservedOrderId,
                                        new BigDecimal(
                                                "100.00000000"
                                        )
                                )
                        )
                        .processedEventIds(
                                Set.of(
                                        "persisted-event"
                                )
                        )
                        .halted(false)
                        .version(10L)
                        .build();

        reconciledState =
                persistedState
                        .toBuilder()
                        .activeReservations(
                                Collections.emptyMap()
                        )
                        .balance(
                                new BigDecimal(
                                        "9900.00000000"
                                )
                        )
                        .totalEquity(
                                new BigDecimal(
                                        "9900.00000000"
                                )
                        )
                        .build();
    }

    @Test
    void shouldRecoverFromPersistedRiskStateWhenSnapshotIsMissing() {

        when(
                stateManager.getState()
        ).thenReturn(
                SystemStateManager.SystemState.RISK_RECOVERING
        );

        when(
                recoveryPort.findLatestSnapshotStateJson(
                        "GLOBAL"
                )
        ).thenReturn(
                Optional.empty()
        );

        when(
                recoveryPort.findPersistedState(
                        "GLOBAL"
                )
        ).thenReturn(
                Optional.of(
                        persistedState
                )
        );

        when(
                recoveryPort.findEventsAfter(
                        "GLOBAL",
                        persistedState.getVersion()
                )
        ).thenReturn(
                Collections.emptyList()
        );

        when(
                recoveryPort.findAllReservations()
        ).thenReturn(
                Collections.emptyList()
        );

        when(
                orderRepositoryPort.findOrderIdsByStatusIn(
                        OrderStateTransitionPolicy
                                .getReconcilableStatuses()
                )
        ).thenReturn(
                Collections.emptySet()
        );

        when(
                exchangeQueryService
                        .getAvailableBalance("USDT")
        ).thenReturn(
                new BigDecimal(
                        "9900.00000000"
                )
        );

        when(
                riskReconciler.reconcile(
                        any(RiskState.class),
                        any(BigDecimal.class)
                )
        ).thenReturn(
                reconciledState
        );

        recoveryService.recover();

        verify(
                recoveryPort
        ).findPersistedState(
                "GLOBAL"
        );

        ArgumentCaptor<RiskState> stateCaptor =
                ArgumentCaptor.forClass(
                        RiskState.class
                );

        verify(
                riskEngine
        ).initialize(
                stateCaptor.capture()
        );

        RiskState initializedState =
                stateCaptor.getValue();

        assertThat(
                initializedState.getBalance()
        ).isEqualByComparingTo(
                "9900.00000000"
        );

        assertThat(
                initializedState.getDailyPnl()
        ).isEqualByComparingTo(
                "12.50"
        );

        assertThat(
                initializedState.getVersion()
        ).isEqualTo(10L);

        assertThat(
                initializedState.isHalted()
        ).isFalse();
    }

    @Test
    void shouldDeserializeCapitalCreditedEvent()
            throws Exception {

        when(
                stateManager.getState()
        ).thenReturn(
                SystemStateManager.SystemState.RISK_RECOVERING
        );

        when(
                recoveryPort.findLatestSnapshotStateJson(
                        "GLOBAL"
                )
        ).thenReturn(
                Optional.empty()
        );

        RiskState baseState =
                RiskState.builder()
                        .balance(
                                new BigDecimal(
                                        "9900.00000000"
                                )
                        )
                        .totalEquity(
                                new BigDecimal(
                                        "9900.00000000"
                                )
                        )
                        .version(10L)
                        .activeReservations(
                                Collections.emptyMap()
                        )
                        .processedEventIds(
                                Collections.emptySet()
                        )
                        .halted(false)
                        .build();

        when(
                recoveryPort.findPersistedState(
                        "GLOBAL"
                )
        ).thenReturn(
                Optional.of(
                        baseState
                )
        );

        UUID orderId =
                UUID.randomUUID();

        RiskEvent.CapitalCredited credited =
                new RiskEvent.CapitalCredited(
                        UUID.randomUUID().toString(),
                        orderId,
                        new BigDecimal("99.35104"),
                        "SELL order fully filled",
                        Instant.now()
                );

        when(
                recoveryPort.findEventsAfter(
                        "GLOBAL",
                        10L
                )
        ).thenReturn(
                java.util.List.of(
                        new RiskEventRecord(
                                "CapitalCredited",
                                "{\"eventId\":\"" +
                                        credited.eventId() +
                                        "\"}"
                        )
                )
        );

        when(
                objectMapper.readValue(
                        eq(
                                "{\"eventId\":\"" +
                                        credited.eventId() +
                                        "\"}"
                        ),
                        eq(
                                RiskEvent.CapitalCredited.class
                        )
                )
        ).thenReturn(
                credited
        );

        RiskState afterCredit =
                baseState
                        .toBuilder()
                        .balance(
                                new BigDecimal(
                                        "9999.35104"
                                )
                        )
                        .totalEquity(
                                new BigDecimal(
                                        "9999.35104"
                                )
                        )
                        .build();

        when(
                reducer.reduce(
                        eq(baseState),
                        eq(credited),
                        eq(true)
                )
        ).thenReturn(
                afterCredit
        );

        when(
                recoveryPort.findAllReservations()
        ).thenReturn(
                Collections.emptyList()
        );

        when(
                orderRepositoryPort.findOrderIdsByStatusIn(
                        OrderStateTransitionPolicy
                                .getReconcilableStatuses()
                )
        ).thenReturn(
                Collections.emptySet()
        );

        when(
                exchangeQueryService
                        .getAvailableBalance("USDT")
        ).thenReturn(
                new BigDecimal(
                        "9999.35104"
                )
        );

        when(
                riskReconciler.reconcile(
                        any(RiskState.class),
                        any(BigDecimal.class)
                )
        ).thenReturn(
                afterCredit
        );

        recoveryService.recover();

        verify(
                objectMapper
        ).readValue(
                eq(
                        "{\"eventId\":\"" +
                                credited.eventId() +
                                "\"}"
                ),
                eq(
                        RiskEvent.CapitalCredited.class
                )
        );

        verify(
                reducer
        ).reduce(
                eq(baseState),
                eq(credited),
                eq(true)
        );

        verify(
                riskEngine
        ).initialize(
                afterCredit
        );
    }
}