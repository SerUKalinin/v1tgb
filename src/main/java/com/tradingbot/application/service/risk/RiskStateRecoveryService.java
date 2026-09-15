package com.tradingbot.application.service.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.domain.policy.OrderStateTransitionPolicy;
import com.tradingbot.domain.risk.RiskEvent;
import com.tradingbot.domain.risk.RiskState;
import com.tradingbot.domain.risk.RiskStateCorruptionException;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Сервис восстановления риск-состояния системы.
 *
 * <p>Отвечает за полный rebuild риск-модели из:
 * <ul>
 *     <li>snapshot состояния</li>
 *     <li>persisted RiskState</li>
 *     <li>event log (event sourcing)</li>
 *     <li>reservation log</li>
 * </ul>
 *
 * <p>После восстановления выполняется реконсиляция с биржей
 * и инициализация RiskEngine.</p>
 *
 * <p>Является критическим компонентом cold-start recovery pipeline.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskStateRecoveryService {

    private final RiskEventRepository eventRepository;
    private final RiskSnapshotRepository snapshotRepository;
    private final RiskReservationLogRepository reservationLogRepository;

    private final RiskStateRepository riskStateRepository;
    private final RiskStateMapper riskStateMapper;

    private final RiskEngine riskEngine;
    private final RiskStateReducer reducer;
    private final ObjectMapper objectMapper;
    private final RiskReconciler riskReconciler;
    private final ExchangeOrderQueryService exchangeQueryService;
    private final SystemStateManager stateManager;
    private final OrderRepositoryPort orderRepositoryPort;

    private static final String AGGREGATE_ID = RiskStateEntity.SINGLETON_ID;
    private static final String CAPITAL_ASSET = "USDT";

    /**
     * Гарантия однократного выполнения recovery.
     */
    private final AtomicBoolean recovered = new AtomicBoolean(false);

    /**
     * Запуск процесса восстановления риск-состояния.
     *
     * <p>Гарантии:
     * <ul>
     *     <li>non-reentrant execution</li>
     *     <li>execution только в состоянии RISK_RECOVERING</li>
     * </ul>
     *
     * @return true если отсутствовали snapshot и event history
     */
    public boolean recover() {

        if (!recovered.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "[RISK-RECOVERY] already executed - non reentrant violation"
            );
        }

        if (stateManager.getState() != SystemStateManager.SystemState.RISK_RECOVERING) {
            throw new IllegalStateException(
                    "[RISK-RECOVERY] invalid state: " + stateManager.getState()
            );
        }

        return recoverState();
    }

    /**
     * Основной pipeline восстановления состояния.
     *
     * <ol>
     *     <li>загрузка snapshot либо persisted RiskState</li>
     *     <li>replay событий</li>
     *     <li>rebuild reservation state</li>
     *     <li>reconciliation с биржей</li>
     *     <li>инициализация RiskEngine</li>
     * </ol>
     */
    private boolean recoverState() {

        log.info("[RISK-RECOVERY] START");

        // ============================================================
        // 1. SNAPSHOT / PERSISTED RISK STATE
        // ============================================================

        Optional<RiskSnapshotEntity> snapshotOpt =
                snapshotRepository.findFirstByAggregateIdOrderByLastVersionDesc(
                        AGGREGATE_ID
                );

        RiskState state;

        if (snapshotOpt.isPresent()) {

            state = deserializeSnapshot(snapshotOpt.get());

            log.info(
                    "[RISK-RECOVERY] source=SNAPSHOT version={}",
                    state.getVersion()
            );

        } else {

            Optional<RiskStateEntity> persistedState =
                    riskStateRepository.findById(AGGREGATE_ID);

            if (persistedState.isPresent()) {

                state = riskStateMapper.toDomain(persistedState.get());

                log.info(
                        "[RISK-RECOVERY] source=PERSISTED_RISK_STATE " +
                                "id={} version={} balance={} reserved={}",
                        AGGREGATE_ID,
                        state.getVersion(),
                        state.getBalance(),
                        state.getReserved()
                );

            } else {

                state = RiskState.empty();

                log.warn(
                        "[RISK-RECOVERY] no snapshot and no persisted risk state " +
                                "-> using empty state"
                );
            }
        }

        // ============================================================
        // 2. EVENT REPLAY
        // ============================================================

        List<RiskEventEntity> events =
                eventRepository.findByAggregateIdAndVersionGreaterThanOrderByVersionAsc(
                        AGGREGATE_ID,
                        state.getVersion()
                );

        log.info(
                "[RISK-RECOVERY] events to replay={}, startingVersion={}",
                events.size(),
                state.getVersion()
        );

        for (RiskEventEntity entity : events) {

            RiskEvent event = deserializeEvent(entity);

            state = reducer.reduce(
                    state,
                    event,
                    true
            );
        }

        // ============================================================
        // 3. RESERVATION REBUILD
        // ============================================================
        //
        // Reservation log является источником истины для:
        // - размера reservation
        // - порядка RESERVE / RELEASE / CONSUME
        //
        // OrderStatus является источником истины для:
        // - того, может ли reservation существовать после recovery.
        //
        // Historical RESERVE от уже terminal order
        // не должен resurrect'ить reservation.
        // ============================================================

        Map<UUID, BigDecimal> reservations = new HashMap<>();

        List<RiskReservationLogEntity> logs =
                reservationLogRepository.findAllByOrderBySequenceIdAsc();

        for (RiskReservationLogEntity logEntity : logs) {

            String eventType = logEntity.getEventType();

            if ("RESERVE".equals(eventType)) {

                reservations.put(
                        logEntity.getOrderId(),
                        logEntity.getAmount()
                );

                continue;
            }

            if ("RELEASE".equals(eventType)
                    || "CONSUME".equals(eventType)) {

                reservations.remove(
                        logEntity.getOrderId()
                );
            }
        }

        // Только эти состояния могут иметь живую reservation.
        //
        // FILLED / REJECTED / CANCELED / ERROR являются terminal.
        // Поэтому старые RESERVE записи от них должны быть отброшены.
        Set<OrderStatus> activeStatuses =
                OrderStateTransitionPolicy.getReconcilableStatuses();

        Set<UUID> activeOrderIds =
                orderRepositoryPort.findOrderIdsByStatusIn(activeStatuses);

        reservations.keySet().retainAll(activeOrderIds);

        state = state.toBuilder()
                .activeReservations(Map.copyOf(reservations))
                .build();

        log.info(
                "[RISK-RECOVERY] reservations={}, reserved={}, activeOrders={}",
                reservations.size(),
                state.getReserved(),
                activeOrderIds.size()
        );

        // ============================================================
        // 4. RECONCILIATION WITH EXCHANGE
        // ============================================================

        state = reconcileWithExchange(state);

        log.info(
                "[RISK-RECOVERY] reconciled balance={}, totalEquity={}",
                state.getAvailableBalance(),
                state.getTotalEquity()
        );

        // ============================================================
        // 5. ENGINE INITIALIZATION
        // ============================================================

        riskEngine.initialize(state);

        log.info(
                "[RISK-RECOVERY] COMPLETE version={}, halted={}, pnl={}",
                state.getVersion(),
                state.isHalted(),
                state.getDailyPnl()
        );

        return snapshotOpt.isEmpty() && events.isEmpty();
    }

    /**
     * Десериализация snapshot состояния риска.
     */
    private RiskState deserializeSnapshot(RiskSnapshotEntity entity) {

        try {

            return objectMapper.readValue(
                    entity.getStateJson(),
                    RiskState.class
            );

        } catch (Exception e) {

            throw new RuntimeException(
                    "snapshot deserialization failed",
                    e
            );
        }
    }

    /**
     * Десериализация persisted risk event.
     */
    private RiskEvent deserializeEvent(RiskEventEntity entity) {

        try {

            Class<? extends RiskEvent> type =
                    switch (entity.getEventType()) {

                        case "TradeExecuted" ->
                                RiskEvent.TradeExecuted.class;

                        case "PriceUpdated" ->
                                RiskEvent.PriceUpdated.class;

                        case "TradingHalted" ->
                                RiskEvent.TradingHalted.class;

                        case "CapitalReserved" ->
                                RiskEvent.CapitalReserved.class;

                        case "CapitalReleased" ->
                                RiskEvent.CapitalReleased.class;

                        case "CapitalConsumed" ->
                                RiskEvent.CapitalConsumed.class;

                        case "CapitalCredited" ->
                                RiskEvent.CapitalCredited.class;

                        default ->
                                throw new IllegalArgumentException(
                                        "unknown event: " + entity.getEventType()
                                );
                    };

            return objectMapper.readValue(
                    entity.getPayload(),
                    type
            );

        } catch (Exception e) {

            throw new RuntimeException(
                    "event deserialization failed",
                    e
            );
        }
    }

    /**
     * Реконсиляция восстановленного состояния с биржей.
     *
     * <p>При критической ошибке:
     * <ul>
     *     <li>система переводится в HALTED</li>
     *     <li>RiskEngine переводится в emergency stop</li>
     * </ul>
     */
    private RiskState reconcileWithExchange(RiskState state) {

        BigDecimal exchangeBalance =
                exchangeQueryService.getAvailableBalance(CAPITAL_ASSET);

        try {

            return riskReconciler.reconcile(
                    state,
                    exchangeBalance
            );

        } catch (RiskStateCorruptionException e) {

            log.error(
                    "[RISK-RECOVERY] reconciliation failed: {}",
                    e.getMessage()
            );

            stateManager.updateState(
                    SystemStateManager.SystemState.HALTED
            );

            riskEngine.emergencyStop(
                    "Reconciliation failure: " + e.getMessage()
            );

            throw e;
        }
    }
}