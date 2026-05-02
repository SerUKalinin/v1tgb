package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class StatePersistenceAdapter {

    private final OrderRepository orderRepository;

    private final OrderMapper orderMapper;

    @Transactional
    public void syncAndSave(Order order, OrderEntity entity) {
        orderMapper.updateEntity(order, entity);
        orderRepository.saveAndFlush(entity);
    }}
