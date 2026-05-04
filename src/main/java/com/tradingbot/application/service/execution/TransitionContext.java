package com.tradingbot.application.service.execution;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Неизменяемый snapshot состояния перехода.
 * Гарантирует консистентность данных на всех этапах pipeline.
 */
@Getter
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class TransitionContext {
    private final UUID orderId;
    private final OrderStatus initialStatus;
    private final OrderStatus targetStatus;
    
    private final OrderSnapshot orderSnapshot;
    private final Order orderReference; // Ссылка для мутации в конце пайплайна
    private final OrderEntity entityReference;
    
    private final Instant timestamp;
    
    public static TransitionContext create(Order order, OrderEntity entity, OrderStatus target) {
        return TransitionContext.builder()
                .orderId(order.getId())
                .initialStatus(order.getStatus())
                .targetStatus(target)
                .orderSnapshot(OrderSnapshot.from(order)) 
                .orderReference(order)
                .entityReference(entity)
                .timestamp(Instant.now())
                .build();
    }
    /**
     * Создает новый snapshot с валидированным статусом.
     * Исходный объект остается неизменным.
     */
    public TransitionContext withValidatedStatus(OrderStatus validatedStatus) {
        return this.toBuilder()
                .targetStatus(validatedStatus)
                .build();
    }
}
