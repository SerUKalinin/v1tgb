package com.tradingbot.application.risk;

import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.Signal;
import com.tradingbot.domain.risk.RiskDecision;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.domain.risk.RiskService;
import com.tradingbot.domain.risk.RiskState;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.ExecutionLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.Optional;

/**
 * Реализация {@link RiskManager}, выступающая как enforcement-gate для торговых решений.
 * <p>
 * Отвечает за:
 * <ul>
 *     <li>валидацию сигналов на основе текущего состояния риска</li>
 *     <li>расчёт допустимого размера позиции</li>
 *     <li>финальное принятие или отклонение торговых решений</li>
 * </ul>
 * <p>
 * Вся логика опирается на {@link RiskService} как источник состояния риска
 * и {@link RiskEngine} как исполнительную систему оценки сигналов.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultRiskManager implements RiskManager {

    private final RiskService riskService;
    private final RiskEngine riskEngine;
    private final ExecutionLogger executionLogger;

    /**
     * Выполняет оценку сигнала и резервирование позиции в рамках execution context.
     *
     * @param context execution-контекст выполнения
     * @param signal входящий торговый сигнал
     * @return опционально созданный {@link Order}, если риск-оценка успешна
     */
    @Override
    public Optional<Order> evaluateAndReserve(ExecutionContext context, SignalEvent signal) {
        return riskEngine.evaluateSignal(context, signal);
    }

    /**
     * Выполняет проверку и оценку сигнала без явного execution context.
     *
     * @param signal торговый сигнал
     * @return опционально созданный {@link Order}, если риск-оценка успешна
     */
    @Override
    public Optional<Order> approveSignal(SignalEvent signal) {
        return riskEngine.evaluateSignal(signal.getExecutionContext(), signal);
    }

    /**
     * Выполняет risk-check для уже созданного ордера.
     *
     * @param order торговый ордер
     * @return решение риска (approve/reject)
     */
    @Override
    public RiskDecision check(Order order) {
        RiskState currentState = riskService.getState();
        if (currentState.isHalted()) {
            return RiskDecision.reject(
                    RiskDecision.Reason.HALTED,
                    "System is HALTED",
                    Collections.singletonList("Halt check")
            );
        }
        return RiskDecision.approve(order.getQuantity());
    }

    /**
     * Выполняет оценку сигнала с расчётом допустимого объёма позиции.
     *
     * @param signal торговый сигнал
     * @return решение риска с рассчитанным объёмом позиции
     */
    @Override
    public RiskDecision evaluate(Signal signal) {
        RiskState currentState = riskService.getState();
        if (currentState.isHalted()) {
            return RiskDecision.reject(
                    RiskDecision.Reason.HALTED,
                    "System is HALTED",
                    Collections.singletonList("Halt check")
            );
        }

        BigDecimal quantity = calculateQuantity(signal, currentState);
        return RiskDecision.approve(quantity);
    }

    /**
     * Проверяет актуальность ранее принятого risk-решения для ордера.
     *
     * @param order торговый ордер
     * @return true, если решение остаётся валидным в текущем risk-state
     */
    @Override
    public boolean isApprovalFresh(Order order) {
        RiskState currentState = riskService.getState();

        if (currentState.isHalted()) {
            log.error("[RiskGate] Stale approval detected: System is HALTED. Order: {}", order.getId());
            return false;
        }

        return true;
    }

    /**
     * Рассчитывает размер позиции на основе состояния риска и цены сигнала.
     * <p>
     * Базовая стратегия: фиксированный риск 1% от капитала на сделку.
     */
    private BigDecimal calculateQuantity(Signal signal, RiskState state) {
        BigDecimal riskPercent = new BigDecimal("0.01");

        BigDecimal baseCapital = state.getTotalEquity().max(state.getBalance());

        if (baseCapital.compareTo(BigDecimal.ZERO) <= 0 ||
                signal.getPrice() == null ||
                signal.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return new BigDecimal("0.001");
        }

        BigDecimal quantity = baseCapital.multiply(riskPercent)
                .divide(signal.getPrice(), 8, RoundingMode.HALF_UP);

        BigDecimal minQty = new BigDecimal("0.001");
        return quantity.compareTo(minQty) < 0 ? minQty : quantity;
    }
}