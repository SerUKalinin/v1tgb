package com.tradingbot.infrastructure.persistence.repository;

import com.tradingbot.domain.risk.RiskEvent;
import com.tradingbot.domain.risk.RiskState;
import com.tradingbot.domain.risk.RiskStatePort;
import com.tradingbot.infrastructure.persistence.entity.RiskEventEntity;
import com.tradingbot.infrastructure.persistence.entity.RiskStateEntity;
import com.tradingbot.infrastructure.persistence.mapper.RiskStateMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@RequiredArgsConstructor
@Slf4j
public class JpaRiskRepository implements RiskStatePort {
    private final RiskStateRepository riskStateRepository;
    private final RiskEventRepository eventRepository;
    private final RiskStateMapper riskStateMapper;
    private final ObjectMapper objectMapper;

    private static final String AGGREGATE_ID = RiskStateEntity.SINGLETON_ID;

    @Override
    @Transactional
    public RiskState get() {
        RiskStateEntity entity = loadOrInit();
        return riskStateMapper.toDomain(entity);
    }

    @Override
    @Transactional
    public void save(RiskState state) {
        RiskStateEntity entity = loadOrInit();
        riskStateMapper.updateEntity(entity, state);
        entity.setUpdatedAt(Instant.now());
        riskStateRepository.saveAndFlush(entity);
    }

    @Override
    public boolean isEventProcessed(UUID eventId) {
        return eventRepository.existsByEventId(eventId);
    }

    @Override
    @Transactional
    public void markEventProcessed(UUID eventId, RiskState state, RiskEvent event) {
        if (eventRepository.existsByEventId(eventId)) {
            log.warn("[RISK] Event {} already exists, skipping event log insert", eventId);
            save(state);
            return;
        }

        // Increment version before saving
        long nextVersion = eventRepository.findMaxVersionByAggregateId(AGGREGATE_ID).orElse(0L) + 1;
        RiskState stateWithVersion = state.toBuilder().version(nextVersion).build();
        
        save(stateWithVersion);
        
        try {
            RiskEventEntity eventEntity = RiskEventEntity.builder()
                    .eventId(eventId)
                    .aggregateId(AGGREGATE_ID)
                    .version(nextVersion)
                    .eventType(event.getClass().getSimpleName())
                    .payload(objectMapper.writeValueAsString(event))
                    .build();
            eventRepository.save(eventEntity);
        } catch (Exception e) {
            throw new RuntimeException("Failed to persist risk event", e);
        }
    }    private RiskStateEntity loadOrInit() {
        return riskStateRepository.findByIdForUpdate(AGGREGATE_ID)
                .orElseGet(() -> {
                    try {
                        RiskStateEntity newEntity = new RiskStateEntity();
                        newEntity.setAvailableBalance(java.math.BigDecimal.ZERO);
                        newEntity.setReservedMargin(java.math.BigDecimal.ZERO);
                        newEntity.setTotalEquity(java.math.BigDecimal.ZERO);
                        newEntity.setHalted(false);
                        newEntity.setActiveReservations(new java.util.HashMap<>());
                        newEntity.setProcessedEventIds(new java.util.HashSet<>());
                        
                        // Ensure version is null for new entity to trigger INSERT
                        newEntity.setVersion(null);
                        
                        return riskStateRepository.saveAndFlush(newEntity);
                    } catch (org.springframework.dao.DataIntegrityViolationException | org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                        log.info("[RISK] Singleton entity already exists, reloading...");
                        return riskStateRepository.findByIdForUpdate(AGGREGATE_ID).orElseThrow();
                    }
                });
    }}