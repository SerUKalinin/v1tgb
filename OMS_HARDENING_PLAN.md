# OMS Production Hardening: FSM + Fencing + Consistency Layer (Stage 3.5)

## 🎯 Цель этапа
Построить отказоустойчивую модель исполнения ордеров на основе централизованной проверки переходов состояний (FSM), атомарного контроля конкурентного доступа (Fencing) и eventual consistency с внешней биржей (Reconciliation).

---

## 🧱 Архитектурные принципы
1. **Database is the Source of Truth**: Состояние ордера всегда определяется БД.
2. **FSM is a Validator, not an Orchestrator**: FSM только валидирует переходы, не изменяя данные напрямую.
3. **Execution is Isolated and Fenced**: Исполнение возможно только при успешном захвате ownership.
4. **Recovery and Reconciliation are passive layers**: Они инициируют проверки, но не принимают самостоятельных решений об исполнении.

---

## 📋 1. DOMAIN LAYER (FSM)
### 1.1 OrderStateMachine (NEW)
Централизованный валидатор переходов состояний.
- **Обязанности**: Проверка допустимости переходов, запрет неконсистентных state transitions.
- **Пример переходов**:
    - `NEW` → `ACCEPTED`
    - `ACCEPTED` → `PENDING_EXECUTION`
    - `PENDING_EXECUTION` → `EXECUTING`
    - `EXECUTING` → `FILLED` / `REJECTED`

### 1.2 OrderEvent (NEW)
Типизированные события для FSM:
- `SIGNAL_ACCEPTED`, `RISK_APPROVED`, `EXECUTION_REQUESTED`, `EXECUTION_STARTED`, `EXECUTION_SUCCESS`, `EXECUTION_FAILED`, `RECOVERY_TRIGGERED`, `EXTERNAL_SYNC`.

---

## 🧱 2. FENCING LAYER (CRITICAL)
### 2.1 OrderEntity changes
Добавить поля для контроля владения и оптимистичных блокировок:
- `executionOwner` (String)
- `executionExpiresAt` (Instant)
- `version` (Long, @Version)

### 2.2 OrderFencingService (NEW)
- **tryAcquire(orderId, owner)**: Атомарный захват ордера через SQL (UPDATE ... WHERE execution_owner IS NULL OR execution_expires_at < NOW()).
- **validateOwner(orderId, owner)**: Проверка, что текущий поток все еще владеет правом на исполнение.

---

## 🧱 3. APPLICATION LAYER (OMS REFACTOR)
### 3.1 OrderManagementService
- **УДАЛИТЬ**: `processedSignals` Map (in-memory), прямые вызовы `executionEngine`, прямые `setStatus()`.
- **НОВЫЙ ПОТОК**: `SignalEvent` → `FSM validation` → `DB persist` → `event publish`.

---

## 🧱 4. EXECUTION LAYER (HARDENING)
### 4.1 OrderExecutionListener
- Добавить `fencing check (tryAcquire)`.
- Добавить `version validation`.
- **Flow**: `AFTER_COMMIT event` → `tryAcquire()` → `executionEngine.execute()` → `update FSM state` → `persist`.

---

## 🧱 5. RECOVERY LAYER (REWRITE)
### 5.1 OrderRecoveryService
- **Новая роль**: Только диспетчер.
- **Запрещено**: Прямое исполнение ордеров, изменение статусов.
- **Разрешено**: Поиск зависших ордеров, публикация `RECOVERY_EVENT`.

---

## 🧱 6. CONSISTENCY LAYER (BINANCE SYNC)
### 6.1 ReconciliationService (NEW)
- Сверка состояния с Binance по `clientOrderId`.
- Выявление drift (DB ≠ Binance).
- Публикация `EXTERNAL_SYNC` event для корректировки состояния через FSM.

---

## 🧪 7. TEST STRATEGY UPDATE
- **State Tests**: Проверка финального состояния БД, а не вызовов моков.
- **Resilience Tests**: Эмуляция падения системы после коммита в БД, проверка восстановления через Recovery + Fencing.

---

## 🚀 Ожидаемый результат
Система гарантирует отсутствие двойных исполнений, гонок состояний и детерминированное восстановление при сбоях.
