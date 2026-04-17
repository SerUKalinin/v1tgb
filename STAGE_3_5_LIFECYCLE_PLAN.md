# 🏁 План реализации Stage 3.5: Trade Lifecycle Engine

Этот документ является финальным руководством по замыканию цикла сделки. 
**Цель:** Обеспечить автоматический выход из позиций по TP/SL с соблюдением принципов детерминированности и идемпотентности.

---

## 1. Модернизация моделей (Data Foundation)
- [ ] **PositionStatus**: Создать enum `NEW, OPEN, CLOSING, CLOSED`.
- [ ] **PositionEntity**: 
    - Добавить `stopLoss` (BigDecimal), `takeProfit` (BigDecimal).
    - Добавить `status` (PositionStatus).
    - Добавить `closeRequestId` (UUID) для идемпотентности закрытия.
- [ ] **TradeCreatedEvent**: Добавить поля `stopLoss` и `takeProfit`.
- [ ] **PositionReducer**: 
    - Правило: TP/SL фиксируются **только** если `status == NEW`.
    - После первого входа статус меняется на `OPEN`.
    - При усреднении (доливке) TP/SL **не меняются**.

## 2. Order Management Service (OMS) — Слой команд
- [ ] Создать `OrderManagementService`.
- [ ] Метод `closePosition(Position position)`:
    - Проверка: `if (status != OPEN) return`.
    - Установка `status = CLOSING`.
    - Генерация `closeRequestId`.
    - Определение реверсивной стороны (BUY -> SELL, SELL -> BUY).
    - Вызов `ExecutionEngine.executeMarketOrder` на **полный объем**.
- [ ] Логирование результата в `closed_positions_log` (опционально для аудита).

## 3. Position Closing Service — Слой мониторинга
- [ ] Создать `PositionClosingService`.
- [ ] Подписка на `NewClosedCandleEvent`.
- [ ] **Логика триггера (High/Low rule)**:
    - `if (candle.high >= position.tp) -> close()`
    - `if (candle.low <= position.sl) -> close()`
- [ ] **Оптимизация**: Использование кэша в памяти, синхронизированного с БД (Source of Truth).

## 4. Исправление Pipeline и User Context
- [ ] **SignalRouter**: Добавить детальный лог: `User: {id}, active={b}, tier={t}`.
- [ ] **UserContextResolver**: Гарантировать чтение актуального статуса пользователя из БД перед роутингом сигнала.
- [ ] **TradingTelegramBot**: В методе `/start` принудительно ставить `active = true` и `tier = FREE`.

---

## 🛡️ Принципы безопасности (Production Ready)
1. **Single Source of Truth**: Всегда доверяем БД. Кэш — только для скорости.
2. **Outcome-State Separation**: Команда на закрытие отделена от факта исполнения.
3. **Immutable TP/SL**: Цели сделки не плывут при доливках.
4. **Intrabar Spikes**: Учитываем High/Low свечи, а не только цену закрытия.

---
**Важно:** После реализации этого плана — СТОП. Никаких новых фич до полной стабилизации этого цикла.
