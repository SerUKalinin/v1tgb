package com.tradingbot.infrastructure.persistence.entity;

/**
 * Lifecycle статусы outbox event.
 * Соответствует PostgreSQL ENUM outbox_status в V1__init.sql.
 */
public enum OutboxStatus {
    PENDING,  // Ожидает обработки OutboxProcessor'ом
    SENT,     // Успешно опубликовано
    FAILED    // Исчерпаны попытки — попало в dead-letter queue
}