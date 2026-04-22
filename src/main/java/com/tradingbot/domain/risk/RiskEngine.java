package com.tradingbot.domain.risk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.infrastructure.persistence.entity.RiskEventEntity;
import com.tradingbot.infrastructure.persistence.entity.RiskSnapshotEntity;
import com.tradingbot.infrastructure.persistence.repository.RiskEventRepository;
import com.tradingbot.infrastructure.persistence.repository.RiskSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class RiskEngine {

    private final RiskEventRepository eventRepository;
    private final RiskSnapshotRepository snapshotRepository;
    private final RiskStateReducer reducer;
    private final ObjectMapper objectMapper;
    private final RiskStateStore riskStateStore;

    private static final String AGGREGATE_ID = "risk_core";
    private static final int SNAPSHOT_THRESHOLD = 500;

    @Transactional
    public void publish(RiskEvent event) {

        RiskState state = riskStateStore.getState();

        // 🔴 HARD GATE: полный стоп системы
        if (state.isHalted() && !(event instanceof RiskEvent.TradingHalted)) {
            log.warn("Risk Engine HALTED. Event ignored: {}", event.getEventId());
            return;
        }

        UUID eventId = parseEventId(event.getEventId());

        // 🔒 идемпотентность
        if (eventRepository.existsByEventId(eventId)) {
            log.info("Duplicate event skipped: {}", event.getEventId());
            return;
        }

        try {
            long nextVersion = resolveNextVersion(state);

            // 1. persist event FIRST (event log is source of truth)
            RiskEventEntity entity = RiskEventEntity.builder()
                    .eventId(eventId)
                    .aggregateId(AGGREGATE_ID)
                    .version(nextVersion)
                    .eventType(event.getClass().getSimpleName())
                    .payload(objectMapper.writeValueAsString(event))
                    .build();

            eventRepository.saveAndFlush(entity);

            // 2. pure state transition
            RiskState newState = reducer.reduce(state, event)
                    .toBuilder()
                    .version(nextVersion)
                    .build();

            riskStateStore.updateInternal(newState);

            // 3. snapshotting (optional optimization)
            if (shouldSnapshot(nextVersion)) {
                takeSnapshot(newState);
            }

        } catch (Exception e) {
            log.error("RiskEngine failed for event {}", event.getEventId(), e);
            throw new RuntimeException("RiskEngine failure", e);
        }
    }

    private long resolveNextVersion(RiskState state) {
        long stateVersion = state.getVersion();
        long dbVersion = eventRepository.findMaxVersionByAggregateId(AGGREGATE_ID)
                .orElse(0L);

        return Math.max(stateVersion, dbVersion) + 1;
    }

    private boolean shouldSnapshot(long version) {
        return version % SNAPSHOT_THRESHOLD == 0;
    }

    private void takeSnapshot(RiskState state) {
        try {
            RiskSnapshotEntity snapshot = RiskSnapshotEntity.builder()
                    .aggregateId(AGGREGATE_ID)
                    .lastVersion(state.getVersion())
                    .stateJson(objectMapper.writeValueAsString(state))
                    .build();

            snapshotRepository.save(snapshot);

            log.info("Snapshot saved at version {}", state.getVersion());

        } catch (JsonProcessingException e) {
            log.error("Snapshot serialization failed", e);
        }
    }

    private UUID parseEventId(String eventId) {
        try {
            return UUID.fromString(eventId);
        } catch (Exception e) {
            return UUID.nameUUIDFromBytes(eventId.getBytes());
        }
    }

    public RiskState getState() {
        return riskStateStore.getState();
    }

    public void initialize(RiskState initialState) {
        riskStateStore.updateInternal(initialState);
    }
}