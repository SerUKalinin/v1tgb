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

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
        int maxRetries = 3;
        int retryCount = 0;
        
        while (retryCount < maxRetries) {
            try {
                processPublish(event);
                return;
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                retryCount++;
                if (retryCount >= maxRetries) {
                    throw new RuntimeException("Failed to publish event after retries due to concurrency", e);
                }
                log.warn("Concurrency conflict for event {}. Retry {}/{}", event.getEventId(), retryCount, maxRetries);
                // В реальной системе здесь можно добавить небольшую паузу (backoff)
            }
        }
    }

    private void processPublish(RiskEvent event) {
        RiskState state = riskStateStore.getState();
        UUID eventUuid = parseEventId(event.getEventId());
        
        // 1. Guard: Kill Switch
        if (state.isHalted() && !(event instanceof RiskEvent.TradingHalted)) {
            log.warn("Risk Engine is HALTED. Ignoring event: {}", event.getEventId());
            return;
        }

        // 2. Idempotency (Global check)
        if (eventRepository.existsByEventId(eventUuid)) {
            log.info("Event {} already processed. Skipping (Idempotency).", event.getEventId());
            return;
        }

        // 3. Persist (Append-only)
        try {
            RiskEventEntity entity = RiskEventEntity.builder()
                    .eventId(eventUuid)
                    .aggregateId(AGGREGATE_ID)
                    .version(state.getVersion() + 1)
                    .eventType(event.getClass().getSimpleName())
                    .payload(objectMapper.writeValueAsString(event))
                    .build();
            eventRepository.save(entity);
        } catch (Exception e) {
            log.error("Failed to persist event {}. Possible concurrent write or DB error.", event.getEventId(), e);
            return;
        }

        // 4. Apply (Pure state transformation)
        RiskState newState = reducer.reduce(state, event);
        riskStateStore.updateInternal(newState);

        // 5. Snapshot (Adaptive)
        if (newState.getVersion() % SNAPSHOT_THRESHOLD == 0) {
            takeSnapshot(newState);
        }
    }

    private UUID parseEventId(String eventId) {
        try {
            return UUID.fromString(eventId);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(eventId.getBytes());
        }
    }

    private void takeSnapshot(RiskState state) {
        try {
            RiskSnapshotEntity snapshot = RiskSnapshotEntity.builder()
                    .aggregateId(AGGREGATE_ID)
                    .lastVersion(state.getVersion())
                    .stateJson(objectMapper.writeValueAsString(state))
                    .build();
            snapshotRepository.save(snapshot);
            log.info("Snapshot taken at version {}", state.getVersion());
        } catch (JsonProcessingException e) {
            log.error("Failed to take snapshot", e);
        }
    }

    public RiskState getState() {
        return riskStateStore.getState();
    }

    public void initialize(RiskState initialState) {
        riskStateStore.updateInternal(initialState);
    }
}
