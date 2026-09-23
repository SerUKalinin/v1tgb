package com.tradingbot.application.risk;

import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.risk.RiskDecision;
import com.tradingbot.domain.risk.RiskEvent;
import com.tradingbot.domain.risk.RiskService;
import com.tradingbot.domain.risk.RiskState;
import com.tradingbot.tracing.ExecutionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Фасадный application-слой поверх RiskService.
 */
@Component
@RequiredArgsConstructor
public class RiskEngine {

    private final RiskService riskService;

    /**
     * Публикует событие риска.
     */
    public void publish(
            RiskEvent event
    ) {
        riskService.publish(event);
    }

    /**
     * Пытается зарезервировать указанную сумму.
     */
    public RiskDecision reserve(
            ExecutionContext context,
            BigDecimal amount
    ) {
        return riskService.reserve(
                context,
                amount
        );
    }

    /**
     * Освобождает ранее зарезервированные средства.
     */
    public void release(
            ExecutionContext context,
            BigDecimal amount,
            String reason
    ) {
        riskService.release(
                context,
                amount,
                reason
        );
    }

    /**
     * Помечает reservation как использованную фактическим исполнением.
     *
     * <p>
     * Legacy/current execution path.
     * Один execution lifecycle использует один deterministic
     * settlement identity.
     */
    public void consumeReservation(
            ExecutionContext context,
            BigDecimal executedNotional,
            String reason
    ) {
        riskService.consumeReservation(
                context,
                executedNotional,
                reason
        );
    }

    /**
     * Потребляет incremental execution settlement.
     *
     * <p>
     * Используется recovery-path, где exchange executedQty
     * является cumulative quantity.
     *
     * @param context execution context
     * @param executedNotional delta notional
     * @param reason причина settlement
     * @param settlementKey deterministic cumulative checkpoint
     */
    public void consumeReservation(
            ExecutionContext context,
            BigDecimal executedNotional,
            String reason,
            String settlementKey
    ) {
        riskService.consumeReservation(
                context,
                executedNotional,
                reason,
                settlementKey
        );
    }

    /**
     * Оценивает сигнал и создаёт Order.
     */
    @Transactional
    public Optional<Order> evaluateSignal(
            ExecutionContext context,
            SignalEvent signal
    ) {
        return riskService.evaluateSignal(
                context,
                signal
        );
    }

    /**
     * Освобождает средства с фиктивной суммой COMPENSATION.
     */
    public void release(
            ExecutionContext context
    ) {
        riskService.release(
                context,
                BigDecimal.ZERO,
                "COMPENSATION"
        );
    }

    /**
     * Синхронизирует текущий баланс.
     */
    public void syncBalance(
            BigDecimal actualBalance
    ) {
        riskService.syncBalance(
                actualBalance
        );
    }

    /**
     * Включает аварийную остановку.
     */
    public void emergencyStop(
            String reason
    ) {
        riskService.emergencyStop(
                reason
        );
    }

    /**
     * Возобновляет торговлю.
     */
    public void resumeTrading() {
        riskService.resumeTrading();
    }

    /**
     * Инициализирует состояние рисков.
     */
    public void initialize(
            RiskState state
    ) {
        riskService.initialize(
                state
        );
    }

    /**
     * Возвращает текущее состояние рисков.
     */
    public RiskState getState() {
        return riskService.getState();
    }
}