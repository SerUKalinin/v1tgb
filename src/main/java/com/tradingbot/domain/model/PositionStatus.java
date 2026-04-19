package com.tradingbot.domain.model;

/**
 * Lifecycle статусы позиции.
 * Соответствует PostgreSQL ENUM position_status в V1__init.sql.
 */
public enum PositionStatus {
    OPEN,     // Позиция активна, накапливается объём
    CLOSING,  // Запрос на закрытие отправлен на биржу
    CLOSED    // Позиция полностью закрыта
}