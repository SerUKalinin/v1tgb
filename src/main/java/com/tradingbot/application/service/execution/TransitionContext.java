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
 * Неизменяемый контекст перехода состояния ордера в pipeline исполнения.
 *
 * <p>Служит единым контейнером данных для проведения безопасного state transition,
 * исключая побочные эффекты и обеспечивая консистентность между доменной моделью
 * и persistence-слоем.</p>
 *
 * <p>Содержит:</p>
 * <ul>
 *     <li>идентификатор ордера</li>
 *     <li>исходный и целевой статус</li>
 *     <li>снимок состояния ордера (OrderSnapshot)</li>
 *     <li>ссылку на доменную модель (для финальной мутации)</li>
 *     <li>ссылку на persistence entity</li>
 *     <li>timestamp перехода</li>
 * </ul>
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
     * Используется для применения финального состояния после валидации pipeline.
     */
    private final Order orderReference;

    /**
     * Ссылка на JPA entity ордера.
     * Используется на persistence уровне для синхронизации состояния.
     */
    private final OrderEntity entityReference;

    /**
     * Время создания контекста перехода.
     */
    private final Instant timestamp;

    /**
     * Создаёт новый контекст перехода состояния ордера.
     *
     * @param order доменная модель ордера
     * @param entity persistence entity ордера
     * @param target целевой статус перехода
     * @return новый неизменяемый TransitionContext
     */
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
     * Создаёт новый контекст с обновлённым целевым статусом после валидации.
     *
     * <p>Используется в pipeline для сохранения immutability исходного контекста
     * и безопасного формирования промежуточных состояний.</p>
     *
     * @param validatedStatus проверенный и разрешённый статус
     * @return новый TransitionContext с обновлённым targetStatus
     */
    public TransitionContext withValidatedStatus(OrderStatus validatedStatus) {
        return this.toBuilder()
                .targetStatus(validatedStatus)
                .build();
    }
}