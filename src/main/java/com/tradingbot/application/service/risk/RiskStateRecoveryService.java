package com.tradingbot.application.service.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.domain.risk.RiskEngine;
import com.tradingbot.domain.risk.RiskEvent;
import com.tradingbot.domain.risk.RiskState;
import com.tradingbot.domain.risk.RiskStateReducer;
import com.tradingbot.infrastructure.persistence.entity.RiskEventEntity;
import com.tradingbot.infrastructure.persistence.entity.RiskReservationLogEntity;
import com.tradingbot.infrastructure.persistence.entity.RiskSnapshotEntity;
import com.tradingbot.infrastructure.persistence.entity.RiskStateEntity;
import com.tradingbot.infrastructure.persistence.repository.RiskEventRepository;
import com.tradingbot.infrastructure.persistence.repository.RiskReservationLogRepository;
import com.tradingbot.infrastructure.persistence.repository.RiskSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Recovers RiskState from database on startup using snapshots and event log.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskStateRecoveryService {
    private final RiskEventRepository eventRepository;
    private final RiskSnapshotRepository snapshotRepository;
    private final RiskReservationLogRepository riskReservationLogRepository;
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
        // IMPORTANT:
        // replay uses sequence_id as deterministic order of intent
        // NOT guaranteed to match DB commit order
        log.info("[RISK-RECOVERY] Starting deterministic risk state recovery (Variant A)...");
        // 1. Load latest snapshot (Source of Truth for Balance/Equity)
        var snapshotOpt = snapshotRepository.findFirstByAggregateIdOrderByLastVersionDesc(AGGREGATE_ID);
        RiskState state = snapshotOpt
                .map(this::deserializeSnapshot)
                .orElse(RiskState.empty());

        log.info("[RISK-RECOVERY] Loaded snapshot at version {}", state.getVersion());

        // 2. Replay tail events (Source of Truth for PnL/Halt state)
        List<RiskEventEntity> tailEvents = eventRepository
                .findByAggregateIdAndVersionGreaterThanOrderByVersionAsc(AGGREGATE_ID, state.getVersion());

        log.info("[RISK-RECOVERY] Replaying {} tail events...", tailEvents.size());

        for (RiskEventEntity entity : tailEvents) {
            RiskEvent event = deserializeEvent(entity);
            state = reducer.reduce(state, event, true);
        }

        // 3. Rebuild activeReservations from risk_reservation_log (Source of Truth for Reservations)
        // sequence_id guarantees deterministic ordering across distributed writes
        Map<UUID, BigDecimal> rebuiltReservations = new HashMap<>();
        List<RiskReservationLogEntity> logs = riskReservationLogRepository.findAllByOrderBySequenceIdAsc();
        
        for (RiskReservationLogEntity logEntry : logs) {            if ("RESERVE".equals(logEntry.getEventType())) {
                rebuiltReservations.put(logEntry.getOrderId(), logEntry.getAmount());
            } else if ("RELEASE".equals(logEntry.getEventType())) {
                rebuiltReservations.remove(logEntry.getOrderId());
            }
        }
        
        // Merge: Overwrite reservations from log, keep balance/pnl from snapshot+events
        state = state.toBuilder()
                .activeReservations(Map.copyOf(rebuiltReservations))
                .build();
        
        log.info("[RISK-RECOVERY] RiskState rebuilt from reservation log: {} active reservations, total reserved: {}", 
                rebuiltReservations.size(), state.getReserved());
        // 4. Final validation
        try {
            state.validateInvariants();
        } catch (IllegalStateException e) {
            log.warn("[RISK-RECOVERY] Recovered state has invariant violations: {}. " +
                    "System will reconcile balance on bootstrap.", e.getMessage());
        }

        // 5. Initialize RiskEngine
        riskEngine.initialize(state);
        log.info("[RISK-RECOVERY] Recovery complete. Final version: {}, Halted: {}, Daily PnL: {}", 
                state.getVersion(), state.isHalted(), state.getDailyPnl());

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
