package com.tradingbot.application.service.order;

import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.IdentityContext;
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

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderWatchdogService {

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final ReconciliationService reconciliationService;

    private static final Duration STUCK_THRESHOLD = Duration.ofMinutes(2);

    @Scheduled(fixedDelay = 60000)
    public void checkStuckOrders() {
        Instant threshold = Instant.now().minus(STUCK_THRESHOLD);

        // Ищем ордера, которые зависли в EXECUTING (уже ушли на биржу, но не подтверждены)
        List<OrderEntity> stuckOrders = orderRepository.findStuckOrders(
                OrderStatus.EXECUTING, 
                threshold
        );
        if (!stuckOrders.isEmpty()) {
            log.warn("[WATCHDOG] Found {} stuck orders in EXECUTING state. Initiating reconciliation...", stuckOrders.size());

            for (OrderEntity orderEntity : stuckOrders) {
                try {
                    log.info("[WATCHDOG][RECOVERY] Reconciling order id={}, clientOrderId={}", 
                            orderEntity.getId(), orderEntity.getClientOrderId());
                    
                    Order domainOrder = orderMapper.toDomain(orderEntity);
                    ExecutionContext context = ExecutionContext.of(domainOrder);
                    
                    reconciliationService.reconcile(context);
                } catch (Exception e) {
                    log.error("[WATCHDOG][ERROR] Failed to recover order {}: {}", orderEntity.getId(), e.getMessage());
                }
            }
        }
    }
}