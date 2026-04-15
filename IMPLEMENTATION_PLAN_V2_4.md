# Implementation Plan v2.4 — Ledger & Recovery

## PHASE 1: Ledger Foundation (P0) — В РАБОТЕ
Цель: Создать надежное хранилище сделок как единственный источник истины.

- [x] Шаг 1. База данных (Flyway V3): `client_order_id`, `exchange_trade_id`, `net_quantity`, `avg_entry_price`.
- [ ] Шаг 2. Domain Layer: Создание чистых моделей `Order`, `Trade`, `Position`.
- [ ] Шаг 3. Trade Ingestion Pipeline: `TradeService` -> `TradeRepository`.
- [ ] Шаг 4. Position Rebuild Engine: `PositionRebuildService` (восстановление из Trade).
- [ ] Шаг 5. Milestone Test: `rebuildStateTest`.

## PHASE 2: Execution & Lifecycle (P1) — ПЛАНИРУЕТСЯ
Цель: Управление ордерами и обработка частичных исполнений.

- [ ] Шаг 1. Order State Machine: NEW -> PARTIALLY_FILLED -> FILLED.
- [ ] Шаг 2. Fill Policy: Обработка остатков ордеров.
- [ ] Шаг 3. Binance Integration: Маппинг ответов биржи в `Trade`.

## PHASE 3: Recovery & Risk (P2) — ПЛАНИРУЕТСЯ
Цель: Сверка с биржей и защита капитала.

- [ ] Шаг 1. Reconciliation Service: Сверка открытых ордеров с Binance.
- [ ] Шаг 2. Kill Switch: Drawdown protection.
- [ ] Шаг 3. Resilience: Resilience4j (Retry, CircuitBreaker).
