package com.tradingbot.domain.model;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.tracing.ExecutionContext;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Доменный порт репозитория ордеров.
 * <p>
 * Определяет контракт для работы с ордерами в контексте execution pipeline,
 * включая захват (claim) ордеров для исполнения, реконсиляции и поиск
 * "зависших" состояний.
 * <p>
 * Реализация обычно содержит атомарные операции и механизмы блокировок
 * для обеспечения идемпотентности и защиты от конкурентного доступа.
 */
public interface OrderRepositoryPort {

    /**
     * Захватывает ордер для исполнения в рамках execution pipeline.
     *
     * @param orderId идентификатор ордера
     * @param context execution контекст (trace + attempt metadata)
     * @return ордер, если захват успешен
     */
    Optional<Order> claimForExecution(UUID orderId, ExecutionContext context);

    /**
     * Захватывает ордер для исполнения в рамках текущей транзакции.
     * <p>
     * Используется для сценариев, где требуется строгая транзакционная
     * согласованность без повторного захвата в разных потоках.
     *
     * @param orderId идентификатор ордера
     * @param context execution контекст
     * @return ордер, если захват успешен
     */
    Optional<Order> claimForExecutionInCurrentTransaction(UUID orderId, ExecutionContext context);

    /**
     * Захватывает ордер для процесса реконсиляции.
     *
     * @param orderId идентификатор ордера
     * @return ордер, если захват успешен
     */
    Optional<Order> claimForReconciliation(UUID orderId);

    /**
     * Находит ордер по идентификатору.
     *
     * @param orderId идентификатор ордера
     * @return ордер, если найден
     */
    Optional<Order> findById(UUID orderId);

    /**
     * Находит "зависшие" ордера в указанных статусах.
     * <p>
     * Используется для recovery/reconciliation механизмов.
     *
     * @param statuses  список статусов для поиска
     * @param threshold временной порог (например, устаревшие записи)
     * @return список ордеров, требующих обработки
     */
    List<Order> findStuckOrdersInStatuses(Set<OrderStatus> statuses, Instant threshold);

    /**
     * Сохраняет состояние ордера.
     *
     * @param order доменный ордер
     */
    void save(Order order);
}