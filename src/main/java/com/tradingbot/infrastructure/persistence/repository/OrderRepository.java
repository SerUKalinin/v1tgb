package com.tradingbot.infrastructure.persistence.repository;

import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<OrderEntity, String> {
    Optional<OrderEntity> findByClientOrderId(String clientOrderId);
    List<OrderEntity> findAllByStatusIn(List<String> statuses);
}