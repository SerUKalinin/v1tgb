package com.tradingbot.common.enums;

/**
 * Статусы жизненного цикла ордера.
 */
public enum OrderStatus {
    NEW,               // Создан в системе
    VALIDATED,         // Прошел проверку риск-менеджмента
    PENDING_EXECUTION, // Ожидает асинхронного исполнения
    EXECUTING,         // В процессе исполнения (атомарный захват)
    SENT,              // Отправлен на биржу
    SENT_TO_EXCHANGE,  // Отправлен в шлюз биржи (ожидание подтверждения)
    PARTIALLY_FILLED,  // Частично исполнен
    FILLED,            // Полностью исполнен
    CANCELED,          // Отменен пользователем или биржей
    REJECTED,          // Отклонен риск-менеджментом или биржей
    UNKNOWN,           // Результат обмена неизвестен (timeout / ambiguous)
    ERROR              // Критическая ошибка при обработке
}
