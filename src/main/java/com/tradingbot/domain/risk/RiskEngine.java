package com.tradingbot.domain.risk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.infrastructure.persistence.entity.RiskEventEntity;
import com.tradingbot.infrastructure.persistence.entity.RiskSnapshotEntity;
import com.tradingbot.infrastructure.persistence.entity.RiskStateEntity;
import com.tradingbot.infrastructure.persistence.repository.RiskEventRepository;
import com.tradingbot.infrastructure.persistence.repository.RiskSnapshotRepository;
import com.tradingbot.infrastructure.persistence.repository.RiskStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class RiskEngine {

    private final RiskEventRepository eventRepository;
    private final RiskSnapshotRepository snapshotRepository;
    private final RiskStateRepository riskStateRepository;
    private final RiskStateReducer reducer;
    private final ObjectMapper objectMapper;
    private final RiskStateStore riskStateStore;

    private static final String AGGREGATE_ID = "risk_core";
    private static final int SNAPSHOT_THRESHOLD = 500;

    // =========================
    // MAIN EVENT PIPELINE
    // =========================

    @Transactional
    public void publish(RiskEvent event) {
        // 1. LOCK AUTHORITY: Блокируем строку агрегата в БД (Pessimistic Lock)
        RiskStateEntity entity = loadOrInitializeRiskState();

        // 2. SYNC: Приводим доменное состояние к состоянию из БД
        RiskState state = mapToDomain(entity);

        // HARD HALT GATE: Fail-closed защита
        if (state.isHalted() && !(event instanceof RiskEvent.TradingHalted)) {
            log.warn("Risk Engine HALTED. Event ignored: {}", event.getEventId());
            return;
        }

        UUID eventId = parseEventId(event.getEventId());

        // IDEMPOTENCY
        if (eventRepository.existsByEventId(eventId)) {
            log.info("Duplicate event skipped: {}", event.getEventId());
            return;
        }

        try {
            // 3. REDUCE: Вычисляем новое состояние через чистую функцию
            RiskState newState = reducer.reduce(state, event);

            // 4. PERSIST STATE: Обновляем Lock Authority в БД
            entity.setTotalEquity(newState.getTotalEquity());
            entity.setAvailableBalance(newState.getAvailableBalance());
            entity.setReservedMargin(newState.getReservedMargin());
            entity.setHalted(newState.isHalted());
            entity.setUpdatedAt(Instant.now());
            riskStateRepository.saveAndFlush(entity);

            // 5. PERSIST EVENT: Сохраняем событие для истории и аудита
            RiskEventEntity eventEntity = RiskEventEntity.builder()
                    .eventId(eventId)
                    .aggregateId(AGGREGATE_ID)
                    .version(entity.getVersion())
                    .eventType(event.getClass().getSimpleName())
                    .payload(objectMapper.writeValueAsString(event))
                    .build();
            eventRepository.save(eventEntity);

            // 6. SYNC CACHE: Обновляем read-only кэш строго ПОСЛЕ коммита транзакции
            syncCacheAfterCommit(newState);

            // Snapshot logic
            if (shouldSnapshot(entity.getVersion())) {
                takeSnapshot(newState);
            }

        } catch (Exception e) {
            log.error("RiskEngine failed for event {}", event.getEventId(), e);
            throw new RuntimeException(e);
        }
    }

    private void syncCacheAfterCommit(RiskState newState) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    riskStateStore.updateCache(newState);
                }
            });
        } else {
            riskStateStore.updateCache(newState);
        }
    }

    // =========================
    // CAPITAL RESERVATION FLOW
    // =========================

    @Transactional(propagation = Propagation.MANDATORY)
    public RiskDecision reserve(UUID orderId, BigDecimal amount) {
        // 1. LOCK AUTHORITY: Блокируем строку агрегата в БД (Pessimistic Lock)
        // Это гарантирует, что проверки лимитов выполняются на самом актуальном состоянии
        RiskStateEntity entity = loadOrInitializeRiskState();
        RiskState state = mapToDomain(entity);

        java.util.List<String> trace = new java.util.ArrayList<>();
        trace.add("Starting risk check for order " + orderId + " with amount " + amount);

        if (state.isHalted()) {
            trace.add("Decision: REJECTED - Risk Engine is HALTED");
            log.warn("[RISK] Reservation rejected for order {}: Engine HALTED", orderId);
            return RiskDecision.reject(RiskDecision.Reason.HALTED, "Risk Engine is HALTED", trace);
        }

        // 1. Daily Loss Check
        BigDecimal dailyLossLimit = state.getTotalEquity().multiply(new BigDecimal("0.05"));
        if (com.tradingbot.domain.risk.RiskState.safeCompare(state.getDailyPnl(), dailyLossLimit.negate()) < 0) {
            trace.add("Decision: REJECTED - Daily loss limit exceeded");
            return RiskDecision.reject(RiskDecision.Reason.DAILY_LIMIT_EXCEEDED, "Daily loss limit exceeded", trace);
        }
        trace.add("Daily loss check passed");

        // 2. Drawdown Check
        BigDecimal currentDrawdown = calculateDrawdown(state);
        if (com.tradingbot.domain.risk.RiskState.safeCompare(currentDrawdown, new BigDecimal("10.0")) > 0) {
            trace.add("Decision: REJECTED - Max drawdown exceeded: " + currentDrawdown + "%");
            return RiskDecision.reject(RiskDecision.Reason.DRAWDOWN_LIMIT_EXCEEDED, "Max drawdown exceeded", trace);
        }
        trace.add("Drawdown check passed");

        // 3. Capital Check
        if (com.tradingbot.domain.risk.RiskState.safeCompare(state.getBalance(), amount) < 0) {
            trace.add("Decision: REJECTED - Insufficient capital. Available: " + state.getBalance());
            return RiskDecision.reject(RiskDecision.Reason.INSUFFICIENT_CAPITAL, "Insufficient capital", trace);
        }
        trace.add("Capital check passed");

        RiskEvent.CapitalReserved event = new RiskEvent.CapitalReserved(
                UUID.randomUUID().toString(),
                orderId,
                amount
        );

        // Внутри publish уже есть логика обновления entity и сохранения события
        publishWithEntity(event, entity);
        
        trace.add("Decision: APPROVED - Capital reserved");
        log.info("[RISK] Capital reserved for order {}: {}. Trace: {}", orderId, amount, trace);
        
        return RiskDecision.approve(amount, trace);
    }

    private void publishWithEntity(RiskEvent event, RiskStateEntity entity) {
        RiskState state = mapToDomain(entity);
        UUID eventId = parseEventId(event.getEventId());

        if (eventRepository.existsByEventId(eventId)) {
            log.info("Duplicate event skipped: {}", event.getEventId());
            return;
        }

        try {
            RiskState newState = reducer.reduce(state, event);

            entity.setTotalEquity(newState.getTotalEquity());
            entity.setAvailableBalance(newState.getAvailableBalance());
            entity.setReservedMargin(newState.getReservedMargin());
            entity.setHalted(newState.isHalted());
            entity.setUpdatedAt(Instant.now());
            riskStateRepository.saveAndFlush(entity);

            RiskEventEntity eventEntity = RiskEventEntity.builder()
                    .eventId(eventId)
                    .aggregateId(AGGREGATE_ID)
                    .version(entity.getVersion())
                    .eventType(event.getClass().getSimpleName())
                    .payload(objectMapper.writeValueAsString(event))
                    .build();
            eventRepository.save(eventEntity);

            syncCacheAfterCommit(newState);

            if (shouldSnapshot(entity.getVersion())) {
                takeSnapshot(newState);
            }
        } catch (Exception e) {
            log.error("RiskEngine failed for event {}", event.getEventId(), e);
            throw new RuntimeException(e);
        }
    }

    private BigDecimal calculateDrawdown(RiskState state) {
        if (com.tradingbot.domain.risk.RiskState.safeCompare(state.getMaxEquity(), BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return state.getMaxEquity()
                .subtract(state.getTotalEquity())
                .divide(state.getMaxEquity(), 4, java.math.RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }

    @Transactional
    public void release(UUID orderId, BigDecimal amount, String reason) {
        RiskEvent.CapitalReleased event = new RiskEvent.CapitalReleased(
                UUID.randomUUID().toString(),
                orderId,
                amount,
                reason
        );

        publish(event);
        log.info("[RISK] Capital released for order {}: {} reason={}", orderId, amount, reason);
    }

    // =========================
    // RECONCILIATION & EMERGENCY
    // =========================

    @Transactional
    public void syncBalance(BigDecimal actualBalance) {
        RiskStateEntity entity = loadOrInitializeRiskState();

        log.info("[RISK] Syncing balance: {} -> {}", entity.getAvailableBalance(), actualBalance);
        entity.setAvailableBalance(actualBalance);
        // При синхронизации баланса обновляем и equity, чтобы не нарушать инварианты
        entity.setTotalEquity(actualBalance.add(entity.getReservedMargin()));
        entity.setUpdatedAt(Instant.now());
        riskStateRepository.saveAndFlush(entity);
        syncCacheAfterCommit(mapToDomain(entity));
    }
    @Transactional
    public void emergencyStop(String reason) {
        RiskStateEntity entity = loadOrInitializeRiskState();

        log.warn("[RISK] EMERGENCY STOP TRIGGERED: {}", reason);
        entity.setHalted(true);
        entity.setUpdatedAt(Instant.now());
        riskStateRepository.saveAndFlush(entity);
        syncCacheAfterCommit(mapToDomain(entity));
    }

    private RiskStateEntity loadOrInitializeRiskState() {
        return riskStateRepository.findByIdForUpdate(AGGREGATE_ID)
                .orElseGet(() -> {
                    log.info("[RISK] Initializing risk core state in DB...");
                    RiskStateEntity newEntity = new RiskStateEntity();
                    newEntity.setId(AGGREGATE_ID);
                    newEntity.setTotalEquity(BigDecimal.ZERO);
                    newEntity.setAvailableBalance(BigDecimal.ZERO);
                    newEntity.setReservedMargin(BigDecimal.ZERO);
                    newEntity.setHalted(false);
                    newEntity.setVersion(0L);
                    newEntity.setUpdatedAt(Instant.now());
                    return riskStateRepository.saveAndFlush(newEntity);
                });
    }

    // =========================
    // UTIL & SNAPSHOTS
    // =========================

    public RiskState mapToDomain(RiskStateEntity entity) {
        return RiskState.builder()
                .totalEquity(entity.getTotalEquity())
                .balance(entity.getAvailableBalance())
                .reserved(entity.getReservedMargin())
                .halted(entity.isHalted())
                .version(entity.getVersion())
                .build();
    }

    private boolean shouldSnapshot(long version) {
        return version > 0 && version % SNAPSHOT_THRESHOLD == 0;
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
        riskStateStore.updateCache(initialState);
    }
}