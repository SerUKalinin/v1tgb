package com.tradingbot.application.service;

import com.tradingbot.domain.model.OrderEntity;
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

    private static final Duration MAX_ORDER_AGE = Duration.ofMinutes(5);

    @Scheduled(fixedDelay = 60000)
    public void checkStuckOrders() {

        Instant threshold = Instant.now().minus(MAX_ORDER_AGE);

        List<OrderEntity> stuckOrders = orderRepository.findAll().stream()
                .filter(o -> o.getCreatedAt().isBefore(threshold) && o.getPrice() == null)
                .toList();

        if (!stuckOrders.isEmpty()) {
            log.warn("[WATCHDOG] stuck orders: {}", stuckOrders.size());

            stuckOrders.forEach(o ->
                    log.warn("Stuck order id={}, symbol={}", o.getId(), o.getSymbol())
            );
        }
    }
}