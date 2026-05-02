package com.tradingbot.domain.risk;

import com.tradingbot.common.util.MoneyMath;
import com.tradingbot.infrastructure.persistence.entity.RiskReservationLogEntity;
import com.tradingbot.infrastructure.persistence.repository.RiskReservationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Оркестратор управления рисками.
 * Отвечает за транзакции, идемпотентность и синхронизацию кэша.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskService {

    private final RiskRepository riskRepository;
    private final RiskStateReducer reducer;
    private final RiskStateStore riskStateStore;
    private final RiskReservationLogRepository riskReservationLogRepository;

    @Transactional
    public void publish(RiskEvent event) {
        // GUARANTEE:
        // event -> reducer -> log -> commit happen in same transaction
        // ensures logical consistency even if commit order differs
        RiskState state = riskRepository.get();
        if (state.isHalted() && !(event instanceof RiskEvent.TradingHalted)) {
            log.warn("[RISK] Engine HALTED. Event ignored: {}", event.getEventId());
            return;
        }

        UUID eventId = parseEventId(event.getEventId());

        if (riskRepository.isEventProcessed(eventId)) {
            log.info("[RISK] Duplicate event skipped: {}", event.getEventId());
            return;
        }

        try {
            // Извлекаем сумму резерва ДО применения редьюсера
            BigDecimal reservedAmountBefore = null;
            if (event instanceof RiskEvent.CapitalReleased e) {
                reservedAmountBefore = state.getActiveReservations().get(e.orderId());
            }

            RiskState newState = reducer.reduce(state, event);
            riskRepository.markEventProcessed(eventId, newState, event);

            // Логирование после успешного применения reducer
            if (event instanceof RiskEvent.CapitalReserved e) {
                logReservation(e.orderId(), "RESERVE", e.amount());
            } else if (event instanceof RiskEvent.CapitalReleased e) {
                if (reservedAmountBefore != null) {
                    logReservation(e.orderId(), "RELEASE", reservedAmountBefore);
                }
            }

            syncCacheAfterCommit(newState);
        } catch (Exception e) {            log.error("[RISK] Processing failed for event {}", event.getEventId(), e);
            throw new RuntimeException("Risk processing failed", e);
        }
    }

    private void logReservation(UUID orderId, String type, BigDecimal amount) {
        riskReservationLogRepository.save(RiskReservationLogEntity.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .eventType(type)
                .amount(amount)
                .createdAt(Instant.now())
                .build());
    }    @Transactional
    public RiskDecision reserve(UUID orderId, BigDecimal amount) {
        RiskState state = riskRepository.get();
        RiskDecision decision = RiskPolicy.canReserve(state, orderId, amount);

        if (decision.isApproved()) {
            RiskEvent.CapitalReserved event = new RiskEvent.CapitalReserved(
                    "RESERVE-" + orderId.toString(),
                    orderId,
                    amount
            );
            publish(event);
        }

        return decision;
    }

    @Transactional
    public void release(UUID orderId, BigDecimal amount, String reason) {
        RiskEvent.CapitalReleased event = new RiskEvent.CapitalReleased(
                "RELEASE-" + orderId.toString(),
                orderId,
                amount,
                reason
        );
        publish(event);
    }

    @Transactional
    public void release(UUID orderId) {
        release(orderId, BigDecimal.ZERO, "COMPENSATION");
    }    @Transactional
    public void syncBalance(BigDecimal actualBalance) {
        RiskState state = riskRepository.get();
        RiskState newState = state.toBuilder()
                .balance(MoneyMath.scale(actualBalance))
                .totalEquity(MoneyMath.add(actualBalance, state.getReservedMargin()))
                .build();

        riskRepository.save(newState);
        syncCacheAfterCommit(newState);
        log.info("[RISK] Balance synced: {}", actualBalance);
    }

    @Transactional
    public void emergencyStop(String reason) {
        RiskState state = riskRepository.get();
        RiskState newState = state.toBuilder().halted(true).build();
        riskRepository.save(newState);
        syncCacheAfterCommit(newState);
        log.error("[RISK] EMERGENCY STOP: {}", reason);
    }

    @Transactional
    public void resumeTrading() {
        RiskState state = riskRepository.get();
        RiskState newState = state.toBuilder().halted(false).build();
        riskRepository.save(newState);
        syncCacheAfterCommit(newState);
        log.info("[RISK] Trading resumed");
    }

    @Transactional
    public void initialize(RiskState state) {
        riskRepository.save(state);
        riskStateStore.updateCache(state);
        log.info("[RISK] State initialized/recovered. Version: {}", state.getVersion());
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
}
