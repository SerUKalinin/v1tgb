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
import com.tradingbot.domain.risk.RiskStateRecoveryPort;
import com.tradingbot.domain.risk.RiskStateReducer;
import com.tradingbot.domain.risk.RiskStateRecoveryPort.RiskEventRecord;
import com.tradingbot.domain.risk.RiskStateRecoveryPort.RiskReservationRecord;
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
 * <p>Отвечает за полный rebuild риск-модели из:</p>
 * <ul>
 *     <li>snapshot состояния;</li>
 *     <li>persisted RiskState;</li>
 *     <li>event log;</li>
 *     <li>reservation log.</li>
 * </ul>
 *
 * <p>После восстановления выполняется reconciliation
 * с биржей и инициализация RiskEngine.</p>
 *
 * <p>Application слой не знает о JPA repository/entity.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskStateRecoveryService {

    /**
     * Persistence boundary.
     *
     * Вся работа с JPA скрыта за этим port.
     */
    private final RiskStateRecoveryPort recoveryPort;

    private final RiskEngine riskEngine;

    private final RiskStateReducer reducer;

    private final ObjectMapper objectMapper;

    private final RiskReconciler riskReconciler;

    private final ExchangeOrderQueryService exchangeQueryService;

    private final SystemStateManager stateManager;

    private final OrderRepositoryPort orderRepositoryPort;

    /**
     * ID singleton RiskState.
     *
     * Значение совпадает с persistence-контрактом RiskStateEntity,
     * но application больше не зависит от entity.
     */
    private static final String AGGREGATE_ID = "GLOBAL";

    private static final String CAPITAL_ASSET = "USDT";

    /**
     * Гарантия однократного выполнения recovery.
     */
    private final AtomicBoolean recovered =
            new AtomicBoolean(false);

    /**
     * Запуск процесса восстановления.
     */
    public boolean recover() {

        if (!recovered.compareAndSet(false, true)) {

            throw new IllegalStateException(
                    "[RISK-RECOVERY] already executed - " +
                            "non reentrant violation"
            );
        }

        if (stateManager.getState()
                != SystemStateManager.SystemState.RISK_RECOVERING) {

            throw new IllegalStateException(
                    "[RISK-RECOVERY] invalid state: " +
                            stateManager.getState()
            );
        }

        return recoverState();
    }

    /**
     * Основной pipeline восстановления.
     */
    private boolean recoverState() {

        log.info(
                "[RISK-RECOVERY] START"
        );

        /*
         * ============================================================
         * 1. SNAPSHOT / PERSISTED RISK STATE
         * ============================================================
         */

        Optional<String> snapshotJsonOpt =
                recoveryPort.findLatestSnapshotStateJson(
                        AGGREGATE_ID
                );

        RiskState state;

        if (snapshotJsonOpt.isPresent()) {

            state =
                    deserializeSnapshot(
                            snapshotJsonOpt.get()
                    );

            log.info(
                    "[RISK-RECOVERY] source=SNAPSHOT version={}",
                    state.getVersion()
            );

        } else {

            Optional<RiskState> persistedState =
                    recoveryPort.findPersistedState(
                            AGGREGATE_ID
                    );

            if (persistedState.isPresent()) {

                state =
                        persistedState.get();

                log.info(
                        "[RISK-RECOVERY] source=PERSISTED_RISK_STATE " +
                                "id={} version={} balance={} reserved={}",
                        AGGREGATE_ID,
                        state.getVersion(),
                        state.getBalance(),
                        state.getReserved()
                );

            } else {

                state =
                        RiskState.empty();

                log.warn(
                        "[RISK-RECOVERY] no snapshot and no persisted " +
                                "risk state -> using empty state"
                );
            }
        }

        /*
         * ============================================================
         * 2. EVENT REPLAY
         * ============================================================
         */

        List<RiskEventRecord> events =
                recoveryPort.findEventsAfter(
                        AGGREGATE_ID,
                        state.getVersion()
                );

        log.info(
                "[RISK-RECOVERY] events to replay={}, startingVersion={}",
                events.size(),
                state.getVersion()
        );

        for (RiskEventRecord record : events) {

            RiskEvent event =
                    deserializeEvent(
                            record
                    );

            state =
                    reducer.reduce(
                            state,
                            event,
                            true
                    );
        }

        /*
         * ============================================================
         * 3. RESERVATION REBUILD
         * ============================================================
         *
         * Reservation log является источником истины для:
         * - размера reservation;
         * - порядка RESERVE / RELEASE / CONSUME.
         *
         * OrderStatus является источником истины для:
         * - того, может ли reservation существовать после recovery.
         * ============================================================
         */

        Map<UUID, BigDecimal> reservations =
                new HashMap<>();

        List<RiskReservationRecord> logs =
                recoveryPort.findAllReservations();

        for (RiskReservationRecord record : logs) {

            String eventType =
                    record.eventType();

            if ("RESERVE".equals(eventType)) {

                reservations.put(
                        record.orderId(),
                        record.amount()
                );

                continue;
            }

            if ("RELEASE".equals(eventType)
                    || "CONSUME".equals(eventType)) {

                reservations.remove(
                        record.orderId()
                );
            }
        }

        /*
         * Только reconcilable order statuses
         * могут иметь живую reservation.
         */
        Set<OrderStatus> activeStatuses =
                OrderStateTransitionPolicy
                        .getReconcilableStatuses();

        Set<UUID> activeOrderIds =
                orderRepositoryPort.findOrderIdsByStatusIn(
                        activeStatuses
                );

        reservations.keySet()
                .retainAll(activeOrderIds);

        state =
                state.toBuilder()
                        .activeReservations(
                                Map.copyOf(
                                        reservations
                                )
                        )
                        .build();

        log.info(
                "[RISK-RECOVERY] reservations={}, reserved={}, activeOrders={}",
                reservations.size(),
                state.getReserved(),
                activeOrderIds.size()
        );

        /*
         * ============================================================
         * 4. RECONCILIATION WITH EXCHANGE
         * ============================================================
         */

        state =
                reconcileWithExchange(
                        state
                );

        log.info(
                "[RISK-RECOVERY] reconciled balance={}, totalEquity={}",
                state.getAvailableBalance(),
                state.getTotalEquity()
        );

        /*
         * ============================================================
         * 5. ENGINE INITIALIZATION
         * ============================================================
         */

        riskEngine.initialize(
                state
        );

        log.info(
                "[RISK-RECOVERY] COMPLETE version={}, halted={}, pnl={}",
                state.getVersion(),
                state.isHalted(),
                state.getDailyPnl()
        );

        return snapshotJsonOpt.isEmpty()
                && events.isEmpty();
    }

    /**
     * Десериализация snapshot.
     */
    private RiskState deserializeSnapshot(
            String stateJson
    ) {

        try {

            return objectMapper.readValue(
                    stateJson,
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
    private RiskEvent deserializeEvent(
            RiskEventRecord record
    ) {

        try {

            Class<? extends RiskEvent> type =
                    switch (record.eventType()) {

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
                                        "unknown event: " +
                                                record.eventType()
                                );
                    };

            return objectMapper.readValue(
                    record.payload(),
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
     * Reconciliation восстановленного state с exchange.
     */
    private RiskState reconcileWithExchange(
            RiskState state
    ) {

        BigDecimal exchangeBalance =
                exchangeQueryService
                        .getAvailableBalance(
                                CAPITAL_ASSET
                        );

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
                    "Reconciliation failure: " +
                            e.getMessage()
            );

            throw e;
        }
    }
}