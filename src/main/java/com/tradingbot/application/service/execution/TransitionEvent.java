package com.tradingbot.application.service.execution;

import com.tradingbot.common.enums.OrderStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable доменное событие, фиксирующее результат перехода состояния ордера.
 *
 * <p>Используется как часть execution pipeline для:
 * <ul>
 *     <li>аудита изменений состояния ордера</li>
 *     <li>трассировки переходов state machine</li>
 *     <li>последующей публикации в наблюдательные или интеграционные системы</li>
 * </ul>
 *
 * <p>Событие отражает итоговый результат перехода и не содержит бизнес-логики.
 */
@Getter
@Builder(toBuilder = false)
public class TransitionEvent {

    private final UUID orderId;
    private final OrderStatus fromStatus;
    private final OrderStatus toStatus;
    private final Instant timestamp;
    private final boolean success;

    /**
     * Создаёт событие перехода состояния на основе TransitionContext.
     *
     * <p>Метод должен вызываться строго после успешного завершения persistence-этапа,
     * чтобы гарантировать соответствие между фактическим состоянием и зафиксированным событием.</p>
     *
     * @param context контекст перехода состояния ордера
     * @return неизменяемое TransitionEvent, отражающее результат перехода
     * @throws IllegalArgumentException если context равен null
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