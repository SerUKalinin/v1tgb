package com.tradingbot.application.service.execution;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.Order;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Неизменяемый контекст перехода состояния ордера
 * в execution pipeline.
 *
 * <p>Контекст содержит только application/domain данные.
 * Persistence Entity намеренно отсутствует.
 *
 * <p>Архитектурные контракты:
 * SYSTEM_CONTRACT.md
 * STATE_MACHINE_CONTRACT.md
 * EXECUTION_ENGINE_CONTRACT.md
 */
@Getter
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class TransitionContext {

    private final UUID orderId;

    private final OrderStatus initialStatus;

    private final OrderStatus targetStatus;

    private final OrderSnapshot orderSnapshot;

    /**
     * Ссылка на доменную модель ордера.
     *
     * <p>Используется execution pipeline для работы
     * с доменным состоянием.
     */
    private final Order orderReference;

    /**
     * Время создания контекста перехода.
     */
    private final Instant timestamp;

    /**
     * Создаёт контекст перехода состояния.
     *
     * @param order доменная модель ордера
     * @param target целевой статус перехода
     * @return immutable TransitionContext
     */
    public static TransitionContext create(
            Order order,
            OrderStatus target
    ) {
        if (order == null) {
            throw new IllegalArgumentException(
                    "Order cannot be null"
            );
        }

        if (target == null) {
            throw new IllegalArgumentException(
                    "Target status cannot be null"
            );
        }

        return TransitionContext.builder()
                .orderId(order.getId())
                .initialStatus(order.getStatus())
                .targetStatus(target)
                .orderSnapshot(OrderSnapshot.from(order))
                .orderReference(order)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * Создаёт новый контекст
     * с валидированным целевым статусом.
     *
     * @param validatedStatus проверенный статус
     * @return новый immutable context
     */
    public TransitionContext withValidatedStatus(
            OrderStatus validatedStatus
    ) {
        if (validatedStatus == null) {
            throw new IllegalArgumentException(
                    "Validated status cannot be null"
            );
        }

        return this.toBuilder()
                .targetStatus(validatedStatus)
                .build();
    }
}