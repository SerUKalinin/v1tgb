package com.tradingbot.domain.model;

import java.util.Optional;
import java.util.UUID;

/**
 * Порт для управления персистентностью ордеров.
 * Определяет операции в терминах доменной модели.
 */
public interface OrderPort {
    Optional<Order> findById(UUID id);
    Optional<Order> findByClientOrderId(String clientOrderId);
    Order save(Order order);
    
    /**
     * Находит ордер с пессимистичной блокировкой для обновления.
     */
    Optional<Order> findByIdForUpdate(UUID id);
}
