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
        UUID eventUuid = parseEventId(event.getEventId());
        
        if (state.isHalted() && !(event instanceof RiskEvent.TradingHalted)) {
            log.warn("Risk Engine is HALTED. Ignoring event: {}", event.getEventId());
            return;
        }

        if (eventRepository.existsByEventId(eventUuid)) {
            log.info("Event {} already processed. Skipping.", event.getEventId());
            return;
        }

        try {
            long nextVersion = state.getVersion() + 1;
            long maxInDb = eventRepository.findMaxVersionByAggregateId(AGGREGATE_ID).orElse(0L);
            if (nextVersion <= maxInDb) {
                nextVersion = maxInDb + 1;
            }

            RiskEventEntity entity = RiskEventEntity.builder()
                    .eventId(eventUuid)
                    .aggregateId(AGGREGATE_ID)
                    .version(nextVersion)
                    .eventType(event.getClass().getSimpleName())
                    .payload(objectMapper.writeValueAsString(event))
                    .build();
            
            eventRepository.save(entity);
            
            RiskState newState = reducer.reduce(state, event)
                    .toBuilder()
                    .version(nextVersion)
                    .build();
            riskStateStore.updateInternal(newState);
            if (nextVersion % SNAPSHOT_THRESHOLD == 0) {
                takeSnapshot(newState);
            }
        } catch (Exception e) {
            log.error("Failed to process risk event {}", event.getEventId(), e);
            throw new RuntimeException("Risk Engine processing failed", e);
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
