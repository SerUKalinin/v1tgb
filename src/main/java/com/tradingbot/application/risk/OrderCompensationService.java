package com.tradingbot.application.risk;

import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.tracing.ExecutionContext;
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

        ExecutionContext context = ExecutionContext.restore(
                order.getSignalId(), // aggregateId
                order.getSignalId(), // correlationId
                order.getSignalId(), // signalId
                order.getId(),
                order.getExecutionId(),
                order.getExecutionId() != null ? order.getExecutionId() : order.getId() // causationId
        );
        riskEngine.release(context, releaseAmount, "Partial fill compensation");
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

        ExecutionContext context = ExecutionContext.restore(
                order.getSignalId(), // aggregateId
                order.getSignalId(), // correlationId
                order.getSignalId(), // signalId
                order.getId(),
                order.getExecutionId(),
                order.getExecutionId() != null ? order.getExecutionId() : order.getId() // causationId
        );
        riskEngine.release(context, releaseAmount, reason);
    }}
