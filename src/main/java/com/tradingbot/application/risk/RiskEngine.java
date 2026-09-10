package com.tradingbot.application.risk;

import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.risk.RiskDecision;
import com.tradingbot.domain.risk.RiskEvent;
import com.tradingbot.domain.risk.RiskState;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.domain.risk.RiskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Фасадный слой поверх RiskService.
 * Используется слоем application для операций управления рисками.
 */
@Component
@RequiredArgsConstructor
public class RiskEngine {

    private final RiskService riskService;

    /**
     * Публикует событие риска в систему.
     *
     * @param event событие риска для публикации
     */
    public void publish(RiskEvent event) {
        riskService.publish(event);
    }

    /**
     * Пытается зарезервировать указанную сумму в контексте исполнения.
     *
     * @param context контекст исполнения
     * @param amount сумма для резерва
     * @return решение по риску (разрешено/отклонено)
     */
    public RiskDecision reserve(ExecutionContext context, BigDecimal amount) {
        return riskService.reserve(context, amount);
    }

    /**
     * Освобождает ранее зарезервированные средства с указанием причины.
     *
     * @param context контекст исполнения
     * @param amount сумма для освобождения
     * @param reason причина освобождения
     */
    public void release(ExecutionContext context, BigDecimal amount, String reason) {
        riskService.release(context, amount, reason);
    }

    /**
     * Оценивает сигнал и возвращает соответствующий заказ, если он сформирован.
     *
     * @param context контекст исполнения
     * @param signal событие сигнала
     * @return опциональный заказ, сформированный на основе сигнала
     */
    public Optional<Order> evaluateSignal(ExecutionContext context, com.tradingbot.domain.event.SignalEvent signal) {
        return riskService.evaluateSignal(context, signal);
    }

    /**
     * Освобождает средства с фиктивной суммой (COMPENSATION).
     *
     * @param context контекст исполнения
     */
    public void release(ExecutionContext context) {
        riskService.release(context, BigDecimal.ZERO, "COMPENSATION");
    }

    /**
     * Синхронизирует текущий баланс с фактическим значением.
     *
     * @param actualBalance фактический баланс
     */
    public void syncBalance(BigDecimal actualBalance) {
        riskService.syncBalance(actualBalance);
    }

    /**
     * Включает аварийную остановку торговли с указанной причиной.
     *
     * @param reason причина остановки
     */
    public void emergencyStop(String reason) {
        riskService.emergencyStop(reason);
    }

    /**
     * Возобновляет торговлю после аварийной остановки.
     */
    public void resumeTrading() {
        riskService.resumeTrading();
    }

    /**
     * Инициализирует состояние рисков.
     *
     * @param state начальное состояние рисков
     */
    public void initialize(RiskState state) {
        riskService.initialize(state);
    }

    /**
     * Возвращает текущее состояние рисков.
     *
     * @return состояние рисков
     */
    public RiskState getState() {
        return riskService.getState();
    }
}