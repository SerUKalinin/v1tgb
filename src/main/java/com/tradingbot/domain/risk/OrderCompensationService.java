package com.tradingbot.domain.risk;

import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Сервис для централизованного управления возвратом (компенсацией) капитала.
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
    public void releasePartial(OrderEntity order, BigDecimal executedQty) {
        if (order == null || order.getQuantity() == null) {
            log.warn("[COMPENSATION] Недостаточно данных для частичного возврата по ордеру {}", 
                    order != null ? order.getId() : "null");
            return;
        }

        if (order.getPrice() == null) {
            throw new IllegalStateException("Невозможно рассчитать возврат: цена ордера null для " + order.getId());
        }

        BigDecimal safeExecutedQty = executedQty != null ? executedQty : BigDecimal.ZERO;
        
        // Защита от отрицательных значений: возвращаем только если original > executed
        if (order.getQuantity().compareTo(safeExecutedQty) <= 0) {
            log.debug("[COMPENSATION] Возврат не требуется: исполненный объем {} >= исходного {}", 
                    safeExecutedQty, order.getQuantity());
            return;
        }

        BigDecimal remainingQty = order.getQuantity().subtract(safeExecutedQty);
        BigDecimal releaseAmount = remainingQty.multiply(order.getPrice());
        
        log.info("[COMPENSATION] Частичный возврат капитала для ордера {}: {} (неисполненный объем: {})", 
                order.getId(), releaseAmount, remainingQty);
        
        riskEngine.release(order.getId(), releaseAmount, "Partial fill compensation");
    }

    /**
     * Полный возврат капитала при отмене или ошибке исполнения ордера.
     * Формула: quantity * price
     */
    public void releaseFull(OrderEntity order, String reason) {
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
    }}
