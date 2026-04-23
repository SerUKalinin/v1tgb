package com.tradingbot.common.enums;

/**
 * Статусы жизненного цикла ордера.
 */
public enum OrderStatus {
    NEW,              // Создан в системе
    VALIDATED,        // Прошел проверку риск-менеджмента
    PENDING_EXECUTION, // Ожидает асинхронного исполнения
    SENT,              // Отправлен на биржу
    PARTIALLY_FILLED,  // Частично исполнен
    FILLED,            // Полностью исполнен    CANCELED,         // Отменен пользователем или биржей
    REJECTED,         // Отклонен риск-менеджментом или биржей
    ERROR             // Критическая ошибка при обработке
}
