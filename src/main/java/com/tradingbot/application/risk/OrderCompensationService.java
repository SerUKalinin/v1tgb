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
 * BUY резервирует quote capital.
 *
 * <p>
 * SELL не резервирует quote capital и после исполнения
 * зачисляет фактическую выручку.
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
     * @param order исходный ордер
     * @param reason причина
     */
    public void releaseFull(
            Order order,
            String reason
    ) {
        BigDecimal releaseAmount =
                order.getQuantity()
                        .multiply(
                                order.getPrice()
                        );

        ExecutionContext context =
                ExecutionContext.of(order);

        riskEngine.release(
                context,
                releaseAmount,
                reason
        );
    }

    /**
     * Полное/обычное settlement исполнения.
     *
     * <p>
     * Используется существующим execution path.
     */
    public void consumeReservation(
            Order order,
            String reason
    ) {

        ExecutionContext context =
                ExecutionContext.of(order);

        if (order.getSide() == OrderSide.BUY) {

            BigDecimal executedQuantity =
                    order.getExecutedQuantity();

            BigDecimal executedPrice =
                    order.getAveragePrice();

            if (executedQuantity == null
                    || executedQuantity.signum() <= 0) {

                throw new IllegalStateException(
                        "BUY settlement requires positive executed quantity. orderId="
                                + order.getId()
                );
            }

            if (executedPrice == null
                    || executedPrice.signum() <= 0) {

                throw new IllegalStateException(
                        "BUY settlement requires positive executed price. orderId="
                                + order.getId()
                );
            }

            BigDecimal executedNotional =
                    executedQuantity.multiply(
                            executedPrice
                    );

            riskEngine.consumeReservation(
                    context,
                    executedNotional,
                    reason
            );

            log.info(
                    "[RISK] Settled BUY notional {} for order {}",
                    executedNotional,
                    order.getId()
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

            riskEngine.publish(
                    event
            );

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

    /**
     * Settlement только новой incremental части исполнения.
     *
     * <p>
     * Ключевая семантика recovery:
     *
     * <pre>
     * cumulative 0.3 -> settle 0.3
     * cumulative 0.6 -> settle next 0.3
     * cumulative 1.0 -> settle next 0.4
     * </pre>
     *
     * <p>
     * settlementKey идентифицирует cumulative checkpoint биржи
     * и является частью deterministic event identity.
     *
     * @param order ордер
     * @param incrementalNotional новое неsettled notional
     * @param settlementKey cumulative checkpoint
     * @param reason причина settlement
     */
    public void settleIncrementalExecution(
            Order order,
            BigDecimal incrementalNotional,
            String settlementKey,
            String reason
    ) {

        if (incrementalNotional == null
                || incrementalNotional.signum() < 0) {

            throw new IllegalArgumentException(
                    "Incremental execution notional cannot be negative. orderId="
                            + order.getId()
            );
        }

        /*
         * Нулевой delta — idempotent no-op.
         *
         * В частности:
         *
         * exchange 0.6
         * internal 0.6
         *
         * повторный recovery не должен трогать RiskState.
         */
        if (incrementalNotional.signum() == 0) {
            log.info(
                    "[RISK] No incremental settlement required for order {}. key={}",
                    order.getId(),
                    settlementKey
            );
            return;
        }

        if (settlementKey == null
                || settlementKey.isBlank()) {

            throw new IllegalArgumentException(
                    "Settlement key is required. orderId="
                            + order.getId()
            );
        }

        ExecutionContext context =
                ExecutionContext.of(order);

        if (order.getSide() == OrderSide.BUY) {

            riskEngine.consumeReservation(
                    context,
                    incrementalNotional,
                    reason,
                    settlementKey
            );

            log.info(
                    "[RISK] Incremental BUY settlement: " +
                            "orderId={}, deltaNotional={}, key={}",
                    order.getId(),
                    incrementalNotional,
                    settlementKey
            );

            return;
        }

        if (order.getSide() == OrderSide.SELL) {

            UUID eventId =
                    IdentityFactory.deriveEventId(
                            context.attempt().executionId(),
                            "capital-credited:" + settlementKey
                    );

            RiskEvent.CapitalCredited event =
                    new RiskEvent.CapitalCredited(
                            eventId.toString(),
                            order.getId(),
                            incrementalNotional,
                            reason
                    );

            riskEngine.publish(
                    event
            );

            log.info(
                    "[RISK] Incremental SELL settlement: " +
                            "orderId={}, proceeds={}, key={}",
                    order.getId(),
                    incrementalNotional,
                    settlementKey
            );

            return;
        }

        throw new IllegalStateException(
                "Unsupported order side for incremental settlement: "
                        + order.getSide()
                        + ", orderId="
                        + order.getId()
        );
    }
}