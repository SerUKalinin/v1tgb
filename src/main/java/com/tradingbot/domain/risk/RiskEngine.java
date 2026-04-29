package com.tradingbot.domain.risk;

import com.tradingbot.common.util.MoneyMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class RiskEngine {

    private final RiskRepository riskRepository;
    private final RiskStateReducer reducer;
    private final RiskStateStore riskStateStore;

    /**
     * Основной конвейер обработки событий риска.
     * Обеспечивает идемпотентность и атомарное обновление состояния.
     */
    @Transactional
    public void publish(RiskEvent event) {
        RiskState state = riskRepository.get();

        // Fail-closed защита: игнорируем события, если система остановлена
        if (state.isHalted() && !(event instanceof RiskEvent.TradingHalted)) {
            log.warn("[RISK] Engine HALTED. Event ignored: {}", event.getEventId());
            return;
        }

        UUID eventId = parseEventId(event.getEventId());

        // Проверка идемпотентности на уровне хранилища
        if (riskRepository.isEventProcessed(eventId)) {
            log.info("[RISK] Duplicate event skipped: {}", event.getEventId());
            return;
        }

        try {
            // Вычисляем новое состояние через чистый редюсер
            RiskState newState = reducer.reduce(state, event);

            // Атомарно сохраняем состояние и помечаем событие как обработанное
            riskRepository.markEventProcessed(eventId, newState, event);

            // Синхронизируем кэш только после успешного коммита транзакции
            syncCacheAfterCommit(newState);

        } catch (Exception e) {
            log.error("[RISK] Processing failed for event {}", event.getEventId(), e);
            throw new RuntimeException("Risk processing failed", e);
        }
    }

    /**
     * Резервирование капитала под ордер.
     */
    @Transactional
    public RiskDecision reserve(UUID orderId, BigDecimal amount) {
        RiskState state = riskRepository.get();
        List<String> trace = new ArrayList<>();
        trace.add("Starting risk check for order " + orderId + " with amount " + amount);

        if (state.isHalted()) {
            return RiskDecision.reject(RiskDecision.Reason.HALTED, "Risk Engine is HALTED", trace);
        }

        if (MoneyMath.isLess(state.getBalance(), amount)) {
            trace.add("Decision: REJECTED - Insufficient capital. Available: " + state.getBalance());
            return RiskDecision.reject(RiskDecision.Reason.INSUFFICIENT_CAPITAL, "Insufficient capital", trace);
        }

        RiskEvent.CapitalReserved event = new RiskEvent.CapitalReserved(
                "RESERVE-" + orderId.toString(),
                orderId,
                amount
        );
        publish(event);

        trace.add("Decision: APPROVED - Capital reserved");
        return RiskDecision.approve(amount, trace);
    }

    /**
     * Освобождение зарезервированного капитала.
     */
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
    /**
     * Упрощенное освобождение (сумма берется из активной резервации).
     */
    @Transactional
    public void release(UUID orderId) {
        release(orderId, BigDecimal.ZERO, "COMPENSATION");
    }

    /**
     * Синхронизация баланса с внешним источником (Reconciliation).
     */
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
    /**
     * Экстренная остановка всех торговых операций.
     */
    @Transactional
    public void emergencyStop(String reason) {
        RiskState state = riskRepository.get();
        RiskState newState = state.toBuilder().halted(true).build();

        riskRepository.save(newState);
        syncCacheAfterCommit(newState);
        log.error("[RISK] EMERGENCY STOP: {}", reason);
    }

    /**
     * Возобновление торговых операций.
     */
    @Transactional
    public void resumeTrading() {
        RiskState state = riskRepository.get();
        RiskState newState = state.toBuilder().halted(false).build();
        
        riskRepository.save(newState);
        syncCacheAfterCommit(newState);
        log.info("[RISK] Trading resumed");
    }

    /**
     * Инициализация состояния (используется при восстановлении системы).
     */
    @Transactional
    public void initialize(RiskState state) {
        riskRepository.save(state);
        riskStateStore.updateCache(state);
        log.info("[RISK] State initialized/recovered. Version: {}", state.getVersion());
    }

    // =========================
    // Вспомогательные методы
    // =========================

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
