package com.tradingbot.domain.model;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepositoryPort {
    Optional<Order> claimForExecution(UUID orderId);
    void save(Order order);
    Optional<Order> findById(UUID orderId);
}
