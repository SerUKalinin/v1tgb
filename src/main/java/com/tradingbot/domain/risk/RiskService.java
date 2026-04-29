package com.tradingbot.domain.risk;

import com.tradingbot.common.util.MoneyMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
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

    @Transactional
    public void publish(RiskEvent event) {
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
            RiskState newState = reducer.reduce(state, event);
            riskRepository.markEventProcessed(eventId, newState, event);
            syncCacheAfterCommit(newState);
        } catch (Exception e) {
            log.error("[RISK] Processing failed for event {}", event.getEventId(), e);
            throw new RuntimeException("Risk processing failed", e);
        }
    }

    @Transactional
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
