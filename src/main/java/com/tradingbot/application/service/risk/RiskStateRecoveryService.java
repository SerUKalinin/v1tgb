package com.tradingbot.application.service.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.application.service.risk.RiskReconciler;
import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import com.tradingbot.domain.risk.RiskEvent;
import com.tradingbot.domain.risk.RiskState;
import com.tradingbot.domain.risk.RiskStateCorruptionException;
import com.tradingbot.domain.risk.RiskStateReducer;
import com.tradingbot.infrastructure.persistence.entity.*;
import com.tradingbot.infrastructure.persistence.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Сервис восстановления риск-состояния системы.
 *
 * <p>Отвечает за полный rebuild риск-модели из:
 * <ul>
 *     <li>snapshot состояния</li>
 *     <li>event log (event sourcing)</li>
 *     <li>reservation log (source of truth для капитала)</li>
 * </ul>
 *
 * <p>После восстановления выполняется реконсиляция с биржей и инициализация RiskEngine.</p>
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
    private final RiskEngine riskEngine;
    private final RiskStateReducer reducer;
    private final ObjectMapper objectMapper;
    private final RiskReconciler riskReconciler;
    private final ExchangeOrderQueryService exchangeQueryService;
    private final SystemStateManager stateManager;

    private static final String AGGREGATE_ID = RiskStateEntity.SINGLETON_ID;
    private static final String CAPITAL_ASSET = "USDT";

    /**
     * Гарантия однократного выполнения recovery.
     */
    private final AtomicBoolean recovered = new AtomicBoolean(false);

    /**
     * Запуск процесса восстановления риск-состояния.
     *
     * <p>Содержит жёсткие инварианты:
     * <ul>
     *     <li>non-reentrant execution</li>
     *     <li>execution только в состоянии RISK_RECOVERING</li>
     * </ul>
     *
     * @return true если система восстановлена впервые (без snapshot + events)
     */
    public boolean recover() {

        // 🔒 защита от повторного запуска
        if (!recovered.compareAndSet(false, true)) {
            throw new IllegalStateException("[RISK-RECOVERY] already executed - non reentrant violation");
        }

        // 🔒 проверка корректного состояния системы
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
     * <p>Шаги:
     * <ol>
     *     <li>загрузка snapshot</li>
     *     <li>replay событий</li>
     *     <li>rebuild reservation state</li>
     *     <li>reconciliation с биржей</li>
     *     <li>инициализация RiskEngine</li>
     * </ol>
     */
    private boolean recoverState() {

        log.info("[RISK-RECOVERY] START");

        // 1. SNAPSHOT
        var snapshotOpt =
                snapshotRepository.findFirstByAggregateIdOrderByLastVersionDesc(AGGREGATE_ID);

        RiskState state = snapshotOpt
                .map(this::deserializeSnapshot)
                .orElse(RiskState.empty());

        log.info("[RISK-RECOVERY] snapshot version={}", state.getVersion());

        // 2. EVENT REPLAY
        List<RiskEventEntity> events =
                eventRepository.findByAggregateIdAndVersionGreaterThanOrderByVersionAsc(
                        AGGREGATE_ID,
                        state.getVersion()
                );

        for (RiskEventEntity entity : events) {
            RiskEvent event = deserializeEvent(entity);
            state = reducer.reduce(state, event, true);
        }

        // 3. RESERVATION REBUILD (SOURCE OF TRUTH)
        Map<UUID, BigDecimal> reservations = new HashMap<>();

        List<RiskReservationLogEntity> logs =
                reservationLogRepository.findAllByOrderBySequenceIdAsc();

        for (RiskReservationLogEntity l : logs) {
            if ("RESERVE".equals(l.getEventType())) {
                reservations.put(l.getOrderId(), l.getAmount());
            } else if ("RELEASE".equals(l.getEventType())) {
                reservations.remove(l.getOrderId());
            }
        }

        state = state.toBuilder()
                .activeReservations(Map.copyOf(reservations))
                .build();

        log.info("[RISK-RECOVERY] reservations={}, reserved={}",
                reservations.size(), state.getReserved());

        // 4. RECONCILIATION WITH EXCHANGE
        state = reconcileWithExchange(state);

        log.info("[RISK-RECOVERY] reconciled balance={}, totalEquity={}",
                state.getAvailableBalance(),
                state.getTotalEquity());

        // 5. ENGINE INITIALIZATION
        riskEngine.initialize(state);

        log.info("[RISK-RECOVERY] COMPLETE version={}, halted={}, pnl={}",
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
            return objectMapper.readValue(entity.getStateJson(), RiskState.class);
        } catch (Exception e) {
            throw new RuntimeException("snapshot deserialization failed", e);
        }
    }

    /**
     * Десериализация доменного risk event из persisted entity.
     */
    private RiskEvent deserializeEvent(RiskEventEntity entity) {
        try {
            Class<? extends RiskEvent> type = switch (entity.getEventType()) {
                case "TradeExecuted" -> RiskEvent.TradeExecuted.class;
                case "PriceUpdated" -> RiskEvent.PriceUpdated.class;
                case "TradingHalted" -> RiskEvent.TradingHalted.class;
                case "CapitalReserved" -> RiskEvent.CapitalReserved.class;
                case "CapitalReleased" -> RiskEvent.CapitalReleased.class;
                default -> throw new IllegalArgumentException("unknown event: " + entity.getEventType());
            };

            return objectMapper.readValue(entity.getPayload(), type);

        } catch (Exception e) {
            throw new RuntimeException("event deserialization failed", e);
        }
    }

    /**
     * Реконсиляция восстановленного состояния с биржей.
     *
     * <p>При критических ошибках:
     * <ul>
     *     <li>перевод системы в HALTED</li>
     *     <li>emergency stop RiskEngine</li>
     * </ul>
     */
    private RiskState reconcileWithExchange(RiskState state) {
        BigDecimal exchangeBalance = exchangeQueryService.getAvailableBalance(CAPITAL_ASSET);
        try {
            return riskReconciler.reconcile(state, exchangeBalance);
        } catch (RiskStateCorruptionException e) {
            log.error("[RISK-RECOVERY] reconciliation failed: {}", e.getMessage());
            stateManager.updateState(SystemStateManager.SystemState.HALTED);
            riskEngine.emergencyStop("Reconciliation failure: " + e.getMessage());
            throw e;
        }
    }
}