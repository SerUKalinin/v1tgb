package com.tradingbot.application.service.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.policy.OrderStateTransitionPolicy;
import com.tradingbot.domain.risk.RiskEvent;
import com.tradingbot.domain.risk.RiskState;
import com.tradingbot.domain.risk.RiskStateReducer;
import com.tradingbot.infrastructure.persistence.entity.RiskEventEntity;
import com.tradingbot.infrastructure.persistence.entity.RiskReservationLogEntity;
import com.tradingbot.infrastructure.persistence.entity.RiskSnapshotEntity;
import com.tradingbot.infrastructure.persistence.entity.RiskStateEntity;
import com.tradingbot.infrastructure.persistence.mapper.RiskStateMapper;
import com.tradingbot.infrastructure.persistence.repository.RiskEventRepository;
import com.tradingbot.infrastructure.persistence.repository.RiskReservationLogRepository;
import com.tradingbot.infrastructure.persistence.repository.RiskSnapshotRepository;
import com.tradingbot.infrastructure.persistence.repository.RiskStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RiskStateRecoveryServiceTest {

    @Mock
    private RiskEventRepository eventRepository;

    @Mock
    private RiskSnapshotRepository snapshotRepository;

    @Mock
    private RiskReservationLogRepository reservationLogRepository;

    @Mock
    private RiskStateRepository riskStateRepository;

    @Mock
    private RiskStateMapper riskStateMapper;

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

    @InjectMocks
    private RiskStateRecoveryService recoveryService;

    private RiskState persistedState;
    private RiskState reconciledState;

    @BeforeEach
    void setUp() {

        UUID reservedOrderId = UUID.randomUUID();

        persistedState = RiskState.builder()
                .balance(new BigDecimal("9900.00000000"))
                .totalEquity(new BigDecimal("10000.00000000"))
                .dailyPnl(new BigDecimal("12.50"))
                .maxEquity(new BigDecimal("10050.00000000"))
                .activeReservations(
                        Map.of(
                                reservedOrderId,
                                new BigDecimal("100.00000000")
                        )
                )
                .processedEventIds(Set.of("persisted-event"))
                .halted(false)
                .version(10L)
                .build();

        reconciledState = persistedState.toBuilder()
                .activeReservations(Collections.emptyMap())
                .balance(new BigDecimal("9900.00000000"))
                .totalEquity(new BigDecimal("9900.00000000"))
                .build();
    }

    @Test
    void shouldRecoverFromPersistedRiskStateWhenSnapshotIsMissing() {

        RiskStateEntity entity = new RiskStateEntity();
        entity.setId(RiskStateEntity.SINGLETON_ID);

        when(stateManager.getState())
                .thenReturn(SystemStateManager.SystemState.RISK_RECOVERING);

        when(snapshotRepository.findFirstByAggregateIdOrderByLastVersionDesc(
                RiskStateEntity.SINGLETON_ID
        )).thenReturn(Optional.empty());

        when(riskStateRepository.findById(RiskStateEntity.SINGLETON_ID))
                .thenReturn(Optional.of(entity));

        when(riskStateMapper.toDomain(entity))
                .thenReturn(persistedState);

        when(eventRepository.findByAggregateIdAndVersionGreaterThanOrderByVersionAsc(
                RiskStateEntity.SINGLETON_ID,
                persistedState.getVersion()
        )).thenReturn(Collections.emptyList());

        when(reservationLogRepository.findAllByOrderBySequenceIdAsc())
                .thenReturn(Collections.emptyList());

        when(orderRepositoryPort.findOrderIdsByStatusIn(
                OrderStateTransitionPolicy.getReconcilableStatuses()
        )).thenReturn(Collections.emptySet());

        when(exchangeQueryService.getAvailableBalance("USDT"))
                .thenReturn(new BigDecimal("9900.00000000"));

        when(riskReconciler.reconcile(
                any(RiskState.class),
                any(BigDecimal.class)
        )).thenReturn(reconciledState);

        recoveryService.recover();

        verify(riskStateRepository)
                .findById(RiskStateEntity.SINGLETON_ID);

        verify(riskStateMapper)
                .toDomain(entity);

        ArgumentCaptor<RiskState> stateCaptor =
                ArgumentCaptor.forClass(RiskState.class);

        verify(riskEngine)
                .initialize(stateCaptor.capture());

        RiskState initializedState =
                stateCaptor.getValue();

        assertThat(initializedState.getBalance())
                .isEqualByComparingTo("9900.00000000");

        assertThat(initializedState.getDailyPnl())
                .isEqualByComparingTo("12.50");

        assertThat(initializedState.getVersion())
                .isEqualTo(10L);

        assertThat(initializedState.isHalted())
                .isFalse();
    }

    @Test
    void shouldDeserializeCapitalCreditedEvent() throws Exception {

        when(stateManager.getState())
                .thenReturn(SystemStateManager.SystemState.RISK_RECOVERING);

        when(snapshotRepository.findFirstByAggregateIdOrderByLastVersionDesc(
                RiskStateEntity.SINGLETON_ID
        )).thenReturn(Optional.empty());

        RiskState baseState = RiskState.builder()
                .balance(new BigDecimal("9900.00000000"))
                .totalEquity(new BigDecimal("9900.00000000"))
                .version(10L)
                .activeReservations(Collections.emptyMap())
                .processedEventIds(Collections.emptySet())
                .halted(false)
                .build();

        RiskStateEntity entity = new RiskStateEntity();
        entity.setId(RiskStateEntity.SINGLETON_ID);

        when(riskStateRepository.findById(RiskStateEntity.SINGLETON_ID))
                .thenReturn(Optional.of(entity));

        when(riskStateMapper.toDomain(entity))
                .thenReturn(baseState);

        UUID orderId = UUID.randomUUID();

        RiskEvent.CapitalCredited credited =
                new RiskEvent.CapitalCredited(
                        UUID.randomUUID().toString(),
                        orderId,
                        new BigDecimal("99.35104"),
                        "SELL order fully filled",
                        Instant.now()
                );

        RiskEventEntity eventEntity =
                RiskEventEntity.builder()
                        .eventId(UUID.fromString(credited.eventId()))
                        .aggregateId(RiskStateEntity.SINGLETON_ID)
                        .version(11L)
                        .eventType("CapitalCredited")
                        .payload("{\"eventId\":\"" + credited.eventId() + "\"}")
                        .build();

        when(eventRepository
                .findByAggregateIdAndVersionGreaterThanOrderByVersionAsc(
                        RiskStateEntity.SINGLETON_ID,
                        10L
                ))
                .thenReturn(java.util.List.of(eventEntity));

        when(objectMapper.readValue(
                eq(eventEntity.getPayload()),
                eq(RiskEvent.CapitalCredited.class)
        )).thenReturn(credited);

        RiskState afterCredit =
                baseState.toBuilder()
                        .balance(new BigDecimal("9999.35104"))
                        .totalEquity(new BigDecimal("9999.35104"))
                        .build();

        when(reducer.reduce(
                eq(baseState),
                eq(credited),
                eq(true)
        )).thenReturn(afterCredit);

        when(reservationLogRepository.findAllByOrderBySequenceIdAsc())
                .thenReturn(Collections.emptyList());

        when(orderRepositoryPort.findOrderIdsByStatusIn(
                OrderStateTransitionPolicy.getReconcilableStatuses()
        )).thenReturn(Collections.emptySet());

        when(exchangeQueryService.getAvailableBalance("USDT"))
                .thenReturn(new BigDecimal("9999.35104"));

        when(riskReconciler.reconcile(
                any(RiskState.class),
                any(BigDecimal.class)
        )).thenReturn(afterCredit);

        recoveryService.recover();

        verify(objectMapper)
                .readValue(
                        eq(eventEntity.getPayload()),
                        eq(RiskEvent.CapitalCredited.class)
                );

        verify(reducer)
                .reduce(
                        eq(baseState),
                        eq(credited),
                        eq(true)
                );

        verify(riskEngine)
                .initialize(afterCredit);
    }
}