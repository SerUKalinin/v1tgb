package com.tradingbot.application.risk;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.risk.RiskEvent;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.IdentityFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Сервис компенсации risk-резерва по ордерам.
 *
 * <p>
 * BUY и SELL имеют разную финансовую семантику:
 * <ul>
 *     <li>BUY резервирует quote capital и после FILLED потребляет reservation;</li>
 *     <li>SELL не резервирует quote capital и после FILLED
 *     зачисляет фактическую выручку в available balance.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCompensationService {

    private final RiskEngine riskEngine;

    /**
     * Частичное освобождение зарезервированного капитала.
     *
     * @param order исходный ордер
     * @param executedQty уже исполненное количество
     */
    public void releasePartial(
            Order order,
            BigDecimal executedQty
    ) {
        BigDecimal remainingQty =
                order.getRemainingQuantity();

        if (remainingQty.compareTo(BigDecimal.ZERO) > 0) {

            BigDecimal releaseAmount =
                    remainingQty.multiply(
                            order.getPrice()
                    );

            ExecutionContext context =
                    ExecutionContext.of(order);

            riskEngine.release(
                    context,
                    releaseAmount,
                    "Partial fill compensation"
            );
        }
    }

    /**
     * Полное освобождение зарезервированного капитала.
     *
     * @param order ордер
     * @param reason причина компенсации
     */
    public void releaseFull(
            Order order,
            String reason
    ) {
        BigDecimal releaseAmount =
                order.getQuantity()
                        .multiply(order.getPrice());

        ExecutionContext context =
                ExecutionContext.of(order);

        riskEngine.release(
                context,
                releaseAmount,
                reason
        );
    }

    /**
     * Фиксирует финансовый результат полного исполнения ордера.
     *
     * <p>
     * BUY:
     * <pre>
     * reservation -> consume
     * </pre>
     *
     * <p>
     * SELL:
     * <pre>
     * executedQuantity * averagePrice -> available balance
     * </pre>
     *
     * @param order полностью исполненный ордер
     * @param reason причина обработки
     */
    public void consumeReservation(
            Order order,
            String reason
    ) {
        ExecutionContext context =
                ExecutionContext.of(order);

        if (order.getSide() == OrderSide.BUY) {

            riskEngine.consumeReservation(
                    context,
                    reason
            );

            return;
        }

        if (order.getSide() == OrderSide.SELL) {

            BigDecimal executedQuantity =
                    order.getExecutedQuantity();

            BigDecimal executedPrice =
                    order.getAveragePrice();

            if (executedQuantity == null
                    || executedQuantity.signum() <= 0) {
                throw new IllegalStateException(
                        "SELL settlement requires positive executed quantity. orderId="
                                + order.getId()
                );
            }

            if (executedPrice == null
                    || executedPrice.signum() <= 0) {
                throw new IllegalStateException(
                        "SELL settlement requires positive executed price. orderId="
                                + order.getId()
                );
            }

            BigDecimal proceeds =
                    executedQuantity.multiply(
                            executedPrice
                    );

            UUID eventId =
                    IdentityFactory.deriveEventId(
                            context.attempt().executionId(),
                            "capital-credited"
                    );

            RiskEvent.CapitalCredited event =
                    new RiskEvent.CapitalCredited(
                            eventId.toString(),
                            order.getId(),
                            proceeds,
                            "SELL order fully filled"
                    );

            riskEngine.publish(event);

            log.info(
                    "[RISK] Credited SELL proceeds {} for order {}",
                    proceeds,
                    order.getId()
            );

            return;
        }

        throw new IllegalStateException(
                "Unsupported order side for risk settlement: "
                        + order.getSide()
                        + ", orderId="
                        + order.getId()
        );
    }
}