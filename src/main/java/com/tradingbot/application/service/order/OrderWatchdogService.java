package com.tradingbot.application.service.order;

import com.tradingbot.application.service.risk.ReconciliationService;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.OrderRepositoryPort;
import com.tradingbot.tracing.ExecutionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Watchdog-сервис для обнаружения зависших ордеров
 * в execution pipeline.
 *
 * <p>Работает только с доменной моделью Order.
 * Persistence детали скрыты за OrderRepositoryPort.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderWatchdogService {

    private static final Duration STUCK_THRESHOLD =
            Duration.ofMinutes(2);

    private final OrderRepositoryPort orderRepositoryPort;
    private final ReconciliationService reconciliationService;

    /**
     * Периодическая проверка зависших ордеров.
     */
    @Scheduled(fixedDelay = 60000)
    public void checkStuckOrders() {

        Instant threshold =
                Instant.now()
                        .minus(STUCK_THRESHOLD);

        List<Order> stuckOrders =
                orderRepositoryPort.findStuckOrdersInStatuses(
                        Set.of(OrderStatus.EXECUTING),
                        threshold
                );

        if (stuckOrders.isEmpty()) {
            return;
        }

        log.warn(
                "[WATCHDOG] Found {} stuck orders in EXECUTING state. " +
                        "Initiating reconciliation...",
                stuckOrders.size()
        );

        for (Order order : stuckOrders) {

            try {

                log.info(
                        "[WATCHDOG][RECOVERY] Reconciling order id={}, clientOrderId={}",
                        order.getId(),
                        order.getClientOrderId()
                );

                ExecutionContext context =
                        ExecutionContext.of(order);

                reconciliationService.reconcile(
                        context
                );

            } catch (Exception e) {

                log.error(
                        "[WATCHDOG][ERROR] Failed to recover order {}: {}",
                        order.getId(),
                        e.getMessage(),
                        e
                );
            }
        }
    }
}