package com.tradingbot.application.service.risk;

import com.tradingbot.common.util.MoneyMath;
import com.tradingbot.domain.risk.RiskState;
import com.tradingbot.domain.risk.RiskStateCorruptionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Компонент реконсиляции риск-состояния.
 *
 * <p>Отвечает за приведение внутреннего {@link RiskState} к согласованному состоянию
 * относительно баланса биржи.</p>
 *
 * <p>Основные задачи:
 * <ul>
 *     <li>обнаружение дрейфа между внутренним и внешним балансом</li>
 *     <li>нормализация финансовых значений</li>
 *     <li>пересчёт total equity</li>
 *     <li>проверка инвариантов риск-модели</li>
 * </ul>
 *
 * <p>Является защитным слоем против рассинхронизации капитала.</p>
 */
@Slf4j
@Component
public class RiskReconciler {

    /**
     * Выполняет реконсиляцию риск-состояния на основе баланса биржи.
     *
     * <p>Алгоритм:
     * <ul>
     *     <li>нормализует входные значения</li>
     *     <li>вычисляет дрейф между внутренним и внешним балансом</li>
     *     <li>пересчитывает total equity</li>
     *     <li>валидирует инварианты состояния</li>
     * </ul>
     *
     * @param state текущее внутреннее риск-состояние
     * @param exchangeBalance баланс, полученный с биржи
     * @return согласованное риск-состояние
     * @throws RiskStateCorruptionException если нарушены инварианты состояния
     */
    public RiskState reconcile(RiskState state, BigDecimal exchangeBalance) {
        BigDecimal canonicalBalance = MoneyMath.scale(exchangeBalance);
        BigDecimal internalBalance = MoneyMath.scale(state.getAvailableBalance());
        BigDecimal drift = canonicalBalance.subtract(internalBalance);

        if (drift.signum() != 0) {
            log.warn("[RISK-RECON] Drift detected: exchangeBalance={} internalBalance={} drift={}",
                    canonicalBalance, internalBalance, drift);
        } else {
            log.info("[RISK-RECON] No drift detected. Internal state already aligned with exchange.");
        }

        BigDecimal reserved = MoneyMath.scale(state.getReserved());
        BigDecimal normalizedTotalEquity = MoneyMath.add(canonicalBalance, reserved);

        RiskState reconciled = state.toBuilder()
                .balance(canonicalBalance)
                .totalEquity(normalizedTotalEquity)
                .build();

        enforceInvariants(reconciled, drift);
        return reconciled;
    }

    /**
     * Проверяет инварианты риск-состояния после реконсиляции.
     *
     * <p>Контрольные проверки:
     * <ul>
     *     <li>балансы не могут быть отрицательными</li>
     *     <li>reserved не может быть отрицательным</li>
     *     <li>totalEquity должен равняться available + reserved</li>
     *     <li>все активные резервации должны быть неотрицательными</li>
     * </ul>
     *
     * @param reconciled приведённое к консистентности состояние
     * @param drift вычисленный дрейф между системами
     * @throws RiskStateCorruptionException при нарушении инвариантов
     */
    private void enforceInvariants(RiskState reconciled, BigDecimal drift) {
        List<String> violations = new ArrayList<>();

        BigDecimal available = MoneyMath.scale(reconciled.getAvailableBalance());
        BigDecimal reserved = MoneyMath.scale(reconciled.getReserved());
        BigDecimal totalEquity = MoneyMath.scale(reconciled.getTotalEquity());

        if (available.compareTo(BigDecimal.ZERO) < 0) {
            violations.add("availableBalance < 0");
        }

        if (reserved.compareTo(BigDecimal.ZERO) < 0) {
            violations.add("reserved < 0");
        }

        BigDecimal sum = MoneyMath.add(available, reserved);
        if (sum.compareTo(totalEquity) != 0) {
            violations.add("availableBalance + reserved != totalEquity");
        }

        Map<UUID, BigDecimal> reservations = reconciled.getActiveReservations();
        if (reservations != null) {
            reservations.forEach((orderId, amount) -> {
                if (MoneyMath.isLess(amount, BigDecimal.ZERO)) {
                    violations.add("active reservation negative for order " + orderId);
                }
            });
        }

        if (!violations.isEmpty()) {
            String message = String.format(
                    "Reconciliation invariant violation: drift=%s, reasons=%s",
                    drift,
                    String.join(", ", violations)
            );

            log.error("[RISK-RECON] {}", message);
            throw new RiskStateCorruptionException(message);
        }
    }
}