package com.tradingbot.domain.policy;

import com.tradingbot.common.enums.OrderStatus;
import java.time.Instant;
import java.util.Set;

/**
 * Доменный порт для валидации переходов состояний ордера.
 * <p>
 * Определяет контракт проверки корректности state transition логики,
 * включая определение терминальных, устаревших и реконсилируемых состояний.
 * Реализации данного интерфейса инкапсулируют правила state machine.
 */
public interface TransitionValidator {

    /**
     * Валидирует переход между текущим и целевым состоянием ордера.
     *
     * @param current текущее состояние ордера
     * @param target  целевое состояние ордера
     * @return итоговое состояние после валидации (обычно target или current)
     */
    OrderStatus validate(OrderStatus current, OrderStatus target);

    /**
     * Проверяет, является ли состояние устаревшим (stale) относительно времени начала обработки.
     *
     * @param status     текущее состояние ордера
     * @param startedAt  время начала обработки
     * @return true, если состояние считается устаревшим
     */
    boolean isStale(OrderStatus status, Instant startedAt);

    /**
     * Проверяет, является ли состояние терминальным.
     *
     * @param status состояние ордера
     * @return true, если дальнейшие переходы невозможны
     */
    boolean isTerminal(OrderStatus status);

    /**
     * Проверяет, является ли состояние уже обработанным системой.
     *
     * @param status состояние ордера
     * @return true, если ордер уже прошёл этап первичной обработки
     */
    boolean isProcessed(OrderStatus status);

    /**
     * Возвращает множество состояний, допустимых для реконсиляции.
     *
     * @return набор реконсилируемых состояний
     */
    Set<OrderStatus> getReconcilableStatuses();
}