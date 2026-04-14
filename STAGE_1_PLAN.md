# Этап 1: Данные и Интеграция с Binance

План реализации Stage 1 на основе архитектурного фидбека.

## 1. Доменные модели (Data-Driven Foundation)
- [x] **Candle** — модель свечи с полями: open, high, low, close, volume, openTime, closeTime, isClosed.
- [x] **CandleWindow** — Value Object для работы с окном свечей. Методы: `getClosePrices()`, `getLast()`, `isReady()`.

## 2. Инфраструктура: Binance Client (Robust Integration)
- [x] **BinanceClient** — реализация HMAC SHA256 подписи, синхронизация времени сервера, Retry + Exponential Backoff.
- [x] **BinanceMarketDataClient** — получение Klines (свечей) из Binance API.

## 3. Слой данных (State & Orchestration)
- [x] **MarketDataCache** — In-memory кэш на базе `Deque` с фиксированным размером (Bounded Cache) и потокобезопасностью (per-symbol locking).
- [x] **MarketDataService** — оркестратор:
    - `warmUp(symbol)`: первичная загрузка истории (100 свечей).
    - `update(symbol, candle)`: обновление кэша (логика update-or-add).
    - `getWindow(symbol)`: получение неизменяемого снимка окна (`List.copyOf`).

## 4. Прикладной слой и Pipeline
- [x] **TradingPipeline** — рефакторинг: переход от `Price` к `CandleWindow`.
- [x] **MarketScheduler** — обновление данных:
    - Первичный warmUp при старте.
    - Периодическое обновление только последней свечи (limit=3 для надежности).

## 5. Исполнение и Идемпотентность
- [x] **OrderRequest** — добавление `clientOrderId` для защиты от дублей.
- [x] **BinanceExecutionEngine** — использование `clientOrderId` при отправке ордеров.

---
**Текущая задача:** Создание доменных моделей `Candle` и `CandleWindow`.
