package com.tradingbot.application.service.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.domain.risk.RiskEvent;
import com.tradingbot.domain.risk.RiskState;
import com.tradingbot.domain.risk.RiskStateReducer;
import com.tradingbot.infrastructure.persistence.entity.*;
import com.tradingbot.infrastructure.persistence.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final SystemStateManager stateManager;

    private static final String AGGREGATE_ID = RiskStateEntity.SINGLETON_ID;

    // HARD SINGLE EXECUTION GUARANTEE
    private final AtomicBoolean recovered = new AtomicBoolean(false);

    public boolean recover() {

        // 🔒 NON-REENTRANT GUARANTEE
        if (!recovered.compareAndSet(false, true)) {
            throw new IllegalStateException("[RISK-RECOVERY] already executed - non reentrant violation");
        }

        // 🔒 STATE MACHINE CONTRACT ENFORCEMENT
        if (stateManager.getState() != SystemStateManager.SystemState.RISK_RECOVERING) {
            throw new IllegalStateException(
                    "[RISK-RECOVERY] invalid state: " + stateManager.getState()
            );
        }

        return recoverState();
    }

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

        // 4. INVARIANTS CHECK
        try {
            state.validateInvariants();
        } catch (Exception e) {
            log.warn("[RISK-RECOVERY] invariant violation: {}", e.getMessage());
        }

        // 5. ENGINE INIT
        riskEngine.initialize(state);

        log.info("[RISK-RECOVERY] COMPLETE version={}, halted={}, pnl={}",
                state.getVersion(),
                state.isHalted(),
                state.getDailyPnl()
        );

        return snapshotOpt.isEmpty() && events.isEmpty();
    }

    private RiskState deserializeSnapshot(RiskSnapshotEntity entity) {
        try {
            return objectMapper.readValue(entity.getStateJson(), RiskState.class);
        } catch (Exception e) {
            throw new RuntimeException("snapshot deserialization failed", e);
        }
    }

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
}