package com.tradingbot.domain.model;

import java.util.Optional;
import java.util.UUID;

/**
 * Доменный порт для работы с персистентностью ордеров.
 * <p>
 * Определяет контракт доступа к хранилищу ордеров в терминах доменной модели,
 * изолируя домен от конкретной реализации базы данных или ORM.
 */
public interface OrderPort {

    /**
     * Находит ордер по его идентификатору.
     *
     * @param id идентификатор ордера
     * @return ордер, если найден
     */
    Optional<Order> findById(UUID id);

    /**
     * Находит ордер по клиентскому идентификатору.
     *
     * @param clientOrderId бизнес-идентификатор ордера
     * @return ордер, если найден
     */
    Optional<Order> findByClientOrderId(String clientOrderId);

    /**
     * Сохраняет или обновляет ордер в хранилище.
     *
     * @param order доменный ордер
     * @return сохранённый ордер (возможно с обновлёнными полями)
     */
    Order save(Order order);

    /**
     * Находит ордер с пессимистичной блокировкой для безопасного обновления.
     * <p>
     * Используется в конкурентных сценариях исполнения для предотвращения
     * race condition при изменении состояния ордера.
     *
     * @param id идентификатор ордера
     * @return ордер с блокировкой, если найден
     */
    Optional<Order> findByIdForUpdate(UUID id);
}