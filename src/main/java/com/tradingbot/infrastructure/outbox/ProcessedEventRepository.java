package com.tradingbot.infrastructure.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Репозиторий для работы с обработанными outbox-событиями.
 *
 * <p>Используется для реализации идемпотентности обработки событий:
 * позволяет определить, был ли уже обработан конкретный eventId.
 *
 * <p>Применяется в механизмах:
 * <ul>
 *     <li>Outbox consumer idempotency check</li>
 *     <li>защита от повторной обработки событий при retry</li>
 * </ul>
 */
@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, UUID> {

    /**
     * Проверяет, существует ли событие с данным идентификатором.
     *
     * @param eventId идентификатор outbox-события
     * @return true, если событие уже было обработано
     */
    boolean existsByEventId(UUID eventId);
}