package com.tradingbot.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.domain.risk.*;
import com.tradingbot.infrastructure.persistence.entity.RiskEventEntity;
import com.tradingbot.infrastructure.persistence.entity.RiskSnapshotEntity;
import com.tradingbot.infrastructure.persistence.entity.RiskStateEntity;
import com.tradingbot.infrastructure.persistence.repository.RiskEventRepository;
import com.tradingbot.infrastructure.persistence.repository.RiskSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Recovers RiskState from database on startup using snapshots and event log.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskStateRecoveryService {
    private final RiskEventRepository eventRepository;
    private final RiskSnapshotRepository snapshotRepository;
    private final RiskEngine riskEngine;
    private final RiskStateReducer reducer;
    private final ObjectMapper objectMapper;

    private static final String AGGREGATE_ID = RiskStateEntity.SINGLETON_ID;

    public boolean recover() {
        return recoverState();
    }

    @Deprecated
    @EventListener(ApplicationReadyEvent.class)
    public boolean recoverState() {
        log.info("[RISK-RECOVERY] Starting deterministic risk state recovery...");

        // 1. Load latest snapshot
        var snapshotOpt = snapshotRepository.findFirstByAggregateIdOrderByLastVersionDesc(AGGREGATE_ID);
        RiskState state = snapshotOpt
                .map(this::deserializeSnapshot)
                .orElse(RiskState.empty());

        log.info("[RISK-RECOVERY] Loaded snapshot at version {}", state.getVersion());

        // 2. Replay tail events
        List<RiskEventEntity> tailEvents = eventRepository
                .findByAggregateIdAndVersionGreaterThanOrderByVersionAsc(AGGREGATE_ID, state.getVersion());

        log.info("[RISK-RECOVERY] Replaying {} tail events...", tailEvents.size());

        for (RiskEventEntity entity : tailEvents) {
            RiskEvent event = deserializeEvent(entity);
            state = reducer.reduce(state, event, true);
        }

        // 3. Final validation after replay
        try {
            state.validateInvariants();
        } catch (IllegalStateException e) {
            log.warn("[RISK-RECOVERY] Recovered state has invariant violations: {}. " +
                    "This is expected if initial balance sync event is missing. " +
                    "System will reconcile balance on bootstrap.", e.getMessage());
        }

        // 4. Initialize RiskEngine with recovered state        riskEngine.initialize(state);
        log.info("[RISK-RECOVERY] Recovery complete. Final version: {}, Halted: {}, Daily PnL: {}", 
                state.getVersion(), state.isHalted(), state.getDailyPnl());

        // Если нет ни снимка, ни событий — значит база пуста (Cold Start)
        return snapshotOpt.isEmpty() && tailEvents.isEmpty();
    }
    private RiskState deserializeSnapshot(RiskSnapshotEntity entity) {
        try {
            return objectMapper.readValue(entity.getStateJson(), RiskState.class);
        } catch (Exception e) {
            log.error("Failed to deserialize snapshot", e);
            return RiskState.empty();
        }
    }

    private RiskEvent deserializeEvent(RiskEventEntity entity) {
        try {
            Class<? extends RiskEvent> eventClass = switch (entity.getEventType()) {
                case "TradeExecuted" -> RiskEvent.TradeExecuted.class;
                case "PriceUpdated" -> RiskEvent.PriceUpdated.class;
                case "TradingHalted" -> RiskEvent.TradingHalted.class;
                case "CapitalReserved" -> RiskEvent.CapitalReserved.class;
                case "CapitalReleased" -> RiskEvent.CapitalReleased.class;
                default -> throw new IllegalArgumentException("Unknown event type: " + entity.getEventType());
            };            return objectMapper.readValue(entity.getPayload(), eventClass);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize event", e);
        }
    }
}
