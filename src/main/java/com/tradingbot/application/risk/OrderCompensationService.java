package com.tradingbot.application.risk;

import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.domain.model.Order;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.IdentityContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Сервис компенсации risk-резерва по ордерам.
 * <p>
 * Отвечает за возврат (release) зарезервированного капитала в RiskEngine
 * при частичном или полном исполнении ордера.
 * <p>
 * Используется как часть execution pipeline для обеспечения
 * корректного финансового баланса при изменении состояния ордера.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCompensationService {

    private final RiskEngine riskEngine;

    /**
     * Частичное освобождение зарезервированного капитала.
     * <p>
     * Вызывается при частичном исполнении ордера.
     *
     * @param order исходный ордер
     * @param executedQty уже исполненное количество (не используется напрямую, но фиксирует факт partial fill)
     */
    public void releasePartial(Order order, BigDecimal executedQty) {

        BigDecimal remainingQty = order.getRemainingQuantity();

        if (remainingQty.compareTo(BigDecimal.ZERO) > 0) {

            BigDecimal releaseAmount = remainingQty.multiply(order.getPrice());

            ExecutionContext context = ExecutionContext.of(order);

            riskEngine.release(
                    context,
                    releaseAmount,
                    "Partial fill compensation"
            );
        }
    }

    /**
     * Полное освобождение зарезервированного капитала.
     * <p>
     * Используется при отмене ордера или его полном закрытии.
     *
     * @param order ордер
     * @param reason причина компенсации
     */
    public void releaseFull(Order order, String reason) {

        BigDecimal releaseAmount =
                order.getQuantity().multiply(order.getPrice());

        ExecutionContext context = ExecutionContext.of(order);

        riskEngine.release(
                context,
                releaseAmount,
                reason
        );
    }
}