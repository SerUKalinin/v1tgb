package com.tradingbot.infrastructure.persistence.entity;

/**
 * Статусы идемпотентности исполнения ордера на бирже.
 * Соответствует PostgreSQL ENUM exec_idempotency_status в V1__init.sql.
 */
public enum ExecutionIdempotencyStatus {
    IN_PROGRESS, // Запрос отправлен на биржу, ожидаем ответ
    SUCCESS,     // Ордер успешно принят биржей
    FAILED       // Биржа вернула ошибку
}