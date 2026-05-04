package com.tradingbot.application.service.execution;

import com.tradingbot.common.enums.OrderStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder(toBuilder = false)
public class TransitionEvent {
    private final UUID orderId;
    private final OrderStatus fromStatus;
    private final OrderStatus toStatus;
    private final Instant timestamp;
    private final boolean success;

    /**
     * Создает неизменяемое событие на основе финального контекста перехода.
     * Должно вызываться строго после успешного завершения этапа persist.
     */
    public static TransitionEvent fromContext(TransitionContext context) {
        if (context == null) throw new IllegalArgumentException("Context cannot be null");
        
        return TransitionEvent.builder()
                .orderId(context.getOrderId())
                .fromStatus(context.getInitialStatus())
                .toStatus(context.getTargetStatus())
                .timestamp(Instant.now())
                .success(true)
                .build();
    }
}
