# SYSTEM_AUDIT.md - Состояние проекта на 15.04.2026

Этот файл содержит полный аудит архитектуры, реализованных компонентов и текущего прогресса. Используйте его для быстрого контекста при старте новой сессии.

## 1. Стек технологий
- **Java 21**, Spring Boot 3.x
- **БД**: H2 (для тестов/дев), PostgreSQL (целевая)
- **Миграции**: Flyway
- **Архитектура**: Domain-Driven Design (DDD) + Event-Driven Architecture

## 2. Архитектурные слои и компоненты

### Domain (Ядро)
- `Candle`, `Signal`, `OrderRequest`, `ExecutionResult`, `Position`, `Trade` — базовые модели.
- `TradingStrategy` (Interface) + `SimpleStrategy` (EMA Crossover).
- `RiskManager` (Interface) + `DefaultRiskManager` (валидация ордеров).
- `ExecutionEngine` (Interface) — абстракция исполнения.

### Application (Бизнес-логика)
- `TradingPipeline` — оркестрация потока данных от свечи до сигнала.
- `OrderManagementService` (OMS) — управление жизненным циклом ордеров.
- `TradeService` — Ledger (регистрация сделок).
- `PositionService` — управление текущими позициями.
- `MarketDataService` — получение котировок.

### Infrastructure (Реализация)
- `BinanceExecutionEngine` — интеграция с API Binance.
- `BacktestExecutionEngine` — симулятор для тестов.
- `Persistence`: JPA репозитории (`OrderRepository`, `TradeRepository`, `PositionRepository`).
- `Mappers`: MapStruct для преобразования Entity <-> Domain.

## 3. История этапов (Completed Stages)

### Этап 0: Foundation & Infrastructure
- [x] Настройка проекта (Spring Boot 3, Java 21, Gradle).
- [x] Базовая доменная модель: `Candle`, `Signal`, `Order`.
- [x] Инфраструктура БД: Flyway, JPA репозитории.
- [x] CI/CD Pipeline (GitHub Actions).

### Этап 1: Market Data & Strategy Pipeline
- [x] Интеграция с Binance API (Market Data).
- [x] Реализация `TradingPipeline`: поток данных от свечи до сигнала.
- [x] `SimpleStrategy`: реализация EMA Crossover.
- [x] `CandleWindow`: механизм скользящего окна для индикаторов.
- [x] Базовый `BacktestExecutionEngine` для симуляции торгов.

### Этап 2: Execution & Lifecycle (В процессе)
Мы находимся в процессе перехода на **полностью реактивную Event-Driven модель**.

### Реализовано:
- Базовый пайплайн: Candle -> Signal -> Order.
- Интеграция RiskManager в OMS.
- Базовая структура Ledger (Trade/Position).
- Flyway миграции для расширенной схемы БД.

### В процессе (Stage 2 Final Plan):
- Внедрение `OrderFilledEvent`, `TradeCreatedEvent`.
- Реализация `PositionService` как State Reducer.
- Идемпотентность через UNIQUE constraints и UUID событий.
- Система Equity и PnL.

## 4. Ключевые файлы для контекста
- `PRODUCT_ROADMAP_RU.md` — общее видение.
- `STAGE_2_FINAL_PLAN.md` — текущий детальный план работ.
- `src/main/resources/db/migration/V2__Enhanced_schema.sql` — текущая схема БД.

## 5. Важные соглашения
- **Никаких прямых вызовов** между Ledger, OMS и Position слоями (только через Spring Events).
- **Idempotency first**: любая операция должна быть безопасна при повторном выполнении.
- **Position как Reducer**: состояние позиции вычисляется на основе сделок.
