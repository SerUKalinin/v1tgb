package com.tradingbot.domain.risk;

import com.tradingbot.domain.model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Сервис для централизованного управления возвратом (компенсацией) капитала.
 * Использует доменную модель Order для расчета неисполненных остатков.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCompensationService {

    private final RiskEngine riskEngine;

    /**
     * Частичный возврат капитала при частичном исполнении ордера.
     * Формула: (originalQuantity - executedQuantity) * price
     */
    public void releasePartial(Order order, BigDecimal executedQty) {
        if (order == null || order.getQuantity() == null || order.getPrice() == null) {
            log.warn("[COMPENSATION] Недостаточно данных для частичного возврата по ордеру {}",
                    order != null ? order.getId() : "null");
            return;
        }

        // Используем доменный метод для получения актуального неисполненного остатка
        BigDecimal remainingQty = order.getRemainingQuantity();

        if (remainingQty.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("[COMPENSATION] Возврат не требуется: ордер полностью исполнен {}", order.getId());
            return;
        }

        BigDecimal releaseAmount = remainingQty.multiply(order.getPrice());

        log.info("[COMPENSATION] Частичный возврат капитала для ордера {}: {} (неисполненный остаток: {})",
                order.getId(), releaseAmount, remainingQty);

        riskEngine.release(order.getId(), releaseAmount, "Partial fill compensation");
    }

    /**
     * Полный возврат капитала при отмене или ошибке исполнения ордера.
     * Формула: quantity * price
     */
    public void releaseFull(Order order, String reason) {
        if (order == null || order.getQuantity() == null) {
            log.warn("[COMPENSATION] Недостаточно данных для полного возврата по ордеру {}",
                    order != null ? order.getId() : "null");
            return;
        }

        if (order.getPrice() == null) {
            throw new IllegalStateException("Невозможно рассчитать возврат: цена ордера null для " + order.getId());
        }

        BigDecimal releaseAmount = order.getQuantity().multiply(order.getPrice());

        // Гарантируем, что сумма >= 0
        if (releaseAmount.compareTo(BigDecimal.ZERO) < 0) {
            log.error("[COMPENSATION] Отрицательная сумма возврата для ордера {}: {}", order.getId(), releaseAmount);
            return;
        }

        log.info("[COMPENSATION] Полный возврат капитала для ордера {}: {} (Причина: {})",
                order.getId(), releaseAmount, reason);

        riskEngine.release(order.getId(), releaseAmount, reason);
    }
}
