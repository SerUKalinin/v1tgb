package com.tradingbot.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.domain.risk.*;
import com.tradingbot.infrastructure.persistence.entity.RiskEventEntity;
import com.tradingbot.infrastructure.persistence.entity.RiskSnapshotEntity;
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

    private static final String AGGREGATE_ID = "risk_core";

    @EventListener(ApplicationReadyEvent.class)
    public void recoverState() {
        log.info("[RISK-RECOVERY] Starting deterministic risk state recovery...");

        // 1. Load latest snapshot
        RiskState state = snapshotRepository.findFirstByAggregateIdOrderByLastVersionDesc(AGGREGATE_ID)
                .map(this::deserializeSnapshot)
                .orElse(RiskState.empty());

        log.info("[RISK-RECOVERY] Loaded snapshot at version {}", state.getVersion());

        // 2. Replay tail events
        List<RiskEventEntity> tailEvents = eventRepository
                .findByAggregateIdAndVersionGreaterThanOrderByVersionAsc(AGGREGATE_ID, state.getVersion());

        log.info("[RISK-RECOVERY] Replaying {} tail events...", tailEvents.size());

        for (RiskEventEntity entity : tailEvents) {
            RiskEvent event = deserializeEvent(entity);
            state = reducer.reduce(state, event);
        }

        // 3. Initialize RiskEngine with recovered state
        riskEngine.initialize(state);

        log.info("[RISK-RECOVERY] Recovery complete. Final version: {}, Halted: {}, Daily PnL: {}", 
                state.getVersion(), state.isHalted(), state.getDailyPnl());
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
                default -> throw new IllegalArgumentException("Unknown event type: " + entity.getEventType());
            };
            return objectMapper.readValue(entity.getPayload(), eventClass);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize event", e);
        }
    }
}
