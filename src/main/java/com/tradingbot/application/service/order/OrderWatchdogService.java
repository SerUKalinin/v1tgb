package com.tradingbot.application.service.order;

import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.application.service.risk.ReconciliationService;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Watchdog-сервис для обнаружения "зависших" ордеров в execution pipeline.
 *
 * <p>Выполняет периодическую проверку ордеров, находящихся в состоянии EXECUTING
 * дольше допустимого времени, и инициирует reconciliation процесс.</p>
 *
 * <p>Является частью recovery subsystem, обеспечивающей устойчивость системы
 * к сбоям исполнения и сетевым/биржевым задержкам.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderWatchdogService {

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final ReconciliationService reconciliationService;

    /**
     * Максимально допустимое время нахождения ордера в состоянии EXECUTING
     * до запуска reconciliation.
     */
    private static final Duration STUCK_THRESHOLD = Duration.ofMinutes(2);

    /**
     * Периодическая проверка зависших ордеров.
     *
     * <p>Алгоритм:
     * <ul>
     *     <li>поиск ордеров в EXECUTING старше threshold</li>
     *     <li>конвертация в доменную модель</li>
     *     <li>запуск reconciliation pipeline</li>
     * </ul>
     */
    @Scheduled(fixedDelay = 60000)
    public void checkStuckOrders() {
        Instant threshold = Instant.now().minus(STUCK_THRESHOLD);

        List<OrderEntity> stuckOrders = orderRepository.findStuckOrders(
                OrderStatus.EXECUTING,
                threshold
        );

        if (!stuckOrders.isEmpty()) {
            log.warn("[WATCHDOG] Found {} stuck orders in EXECUTING state. Initiating reconciliation...", stuckOrders.size());

            for (OrderEntity orderEntity : stuckOrders) {
                try {
                    log.info(
                            "[WATCHDOG][RECOVERY] Reconciling order id={}, clientOrderId={}",
                            orderEntity.getId(),
                            orderEntity.getClientOrderId()
                    );

                    Order domainOrder = orderMapper.toDomain(orderEntity);
                    ExecutionContext context = ExecutionContext.of(domainOrder);

                    reconciliationService.reconcile(context);

                } catch (Exception e) {
                    log.error(
                            "[WATCHDOG][ERROR] Failed to recover order {}: {}",
                            orderEntity.getId(),
                            e.getMessage(),
                            e
                    );
                }
            }
        }
    }
}