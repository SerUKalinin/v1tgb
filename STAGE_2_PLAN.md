Persistence, Recovery & Order Management System (OMS)
1. Инфраструктура и Схема БД (Flyway)
   Миграция V1 (Immutable Ledger & State):
   orders: id, client_order_id (UNIQUE), symbol, side, status (PENDING, NEW, PARTIALLY_FILLED, FILLED, CANCELED, REJECTED, EXPIRED), strategy_id, created_at.
   trades: id, external_trade_id (UNIQUE), order_id, symbol, side, price, quantity, commission, commission_asset, strategy_id, executed_at, sequence_id (гарантия порядка).
   positions: symbol, strategy_id, quantity (signed), entry_price, realized_pnl, version (Optimistic Locking), updated_at.
   equity_curve: id, strategy_id, balance, equity, unrealized_pnl, realized_pnl, fees_total, exposure, drawdown, timestamp (INDEX).
2. Order State Machine & Watchdog
   Order Lifecycle: Реализовать переходы состояний с учетом ответов Binance.
   Watchdog Service: Фоновый процесс, который проверяет PENDING ордера (>10 сек) через запрос к Binance API (getOrder) перед отменой/обновлением.
   Persistence Flow: Сохранение ордера в БД в статусе PENDING до вызова API.
3. Core Logic: Position Engine
   applyTrade(TradeEntity trade):
   Математика Position Flip и Partial Close на основе signedQuantity.
   Учет комиссий (хранение в оригинале + логирование).
   Unit-тесты: Покрытие сценариев Long -> Flip -> Short, частичное закрытие, учет комиссий.
4. Recovery & Reconciliation (Сверка)
   OrderRecoveryService: Синхронизация NEW/PARTIALLY_FILLED ордеров при старте.
   PositionSyncService (Drift Policy):
   Soft drift (< 0.1%) -> Alert.
   Hard drift (> 0.1%) -> Kill-switch (остановка стратегии).
5. Analytics & Strategy Isolation
   EquityService: Снапшоты состояния (Balance, Equity, Fees, Exposure, Drawdown).
   Multi-strategy: Изоляция позиций и ордеров через strategy_id.