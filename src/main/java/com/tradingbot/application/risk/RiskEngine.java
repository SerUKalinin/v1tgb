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
 *
 * <p>Именно application-слой владеет транзакционной границей.
 * Доменный RiskService не зависит от Spring.</p>
 */
@Component
@RequiredArgsConstructor
public class RiskEngine {

    private final RiskService riskService;

    /**
     * Публикует событие риска.
     *
     * @param event событие риска
     */
    public void publish(RiskEvent event) {
        riskService.publish(event);
    }

    /**
     * Пытается зарезервировать указанную сумму.
     *
     * @param context execution context
     * @param amount сумма
     * @return решение по риску
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
     *
     * @param context execution context
     * @param amount сумма
     * @param reason причина
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
     * @param context execution context
     * @param executedNotional фактический notional
     * @param reason причина
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
     * Оценивает сигнал и создаёт Order.
     *
     * <p>Транзакционная граница находится в application-слое,
     * а не в domain.</p>
     *
     * @param context execution context
     * @param signal торговый сигнал
     * @return созданный Order
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
     *
     * @param context execution context
     */
    public void release(ExecutionContext context) {
        riskService.release(
                context,
                BigDecimal.ZERO,
                "COMPENSATION"
        );
    }

    /**
     * Синхронизирует текущий баланс.
     *
     * @param actualBalance фактический баланс
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
     *
     * @param reason причина
     */
    public void emergencyStop(String reason) {
        riskService.emergencyStop(reason);
    }

    /**
     * Возобновляет торговлю.
     */
    public void resumeTrading() {
        riskService.resumeTrading();
    }

    /**
     * Инициализирует состояние рисков.
     *
     * @param state начальное состояние
     */
    public void initialize(RiskState state) {
        riskService.initialize(state);
    }

    /**
     * Возвращает текущее состояние рисков.
     *
     * @return RiskState
     */
    public RiskState getState() {
        return riskService.getState();
    }
}