# OMS Stage 3 — FINAL STABILIZATION PLAN (RELEASE READY)

## 🎯 Цель Stage 3
Сделать систему стабильной, безопасной от дублей в 95–99% кейсов, готовой к testnet/paper.

---

## 🔴 FIX 1 — УБРАТЬ ДВОЙНОЙ ENTRY POINT (КРИТИЧНО)
**📍 OrderManagementService**
- Оставить только SignalEvent flow.
- `@Deprecated` для `executeOrder(OrderRequest)` с выбросом `IllegalStateException`.
- Удалить вызовы `executeOrder` из других сервисов.

**✔ DONE CRITERIA**
- 1 вход в систему: `onSignal()`.
- Нет вызовов `executeOrder` в runtime flow.

---

## 🔴 FIX 2 — СТАБИЛИЗИРОВАТЬ DEDUP (MINIMUM VIABLE SAFETY)
**📍 processedSignals**
- Заменить key на: `symbol + ":" + strategyId + ":" + candleTime`.

**✔ DONE CRITERIA**
- Нет дублей одной стратегии на одну свечу.
- Нет повторной обработки одного сигнала.

---

## 🔴 FIX 3 — DB IDEMPOTENCY (CRITICAL SAFETY LAYER)
**📍 OrderRepository**
- Добавить: `boolean existsByClientOrderId(String clientOrderId);`.

**📍 OMS execution BEFORE send**
- Проверка существования ордера перед отправкой на биржу.

**✔ DONE CRITERIA**
- Restart-safe execution.
- Retry-safe execution.

---

## 🟡 FIX 4 — POSITION GUARD (MINOR REFACTOR)
**📍 OrderManagementService**
- Проверка текущей позиции перед `processSignal`.
- Если позиция открыта в ту же сторону — игнорировать сигнал.

**✔ DONE CRITERIA**
- Нет повторного входа в одну позицию в нормальных условиях.

---

## 🟡 FIX 5 — CLEAN FLOW (КРИТИЧНО ДЛЯ ПОДДЕРЖКИ)
**📍 Единый pipeline**
`SignalEvent` → `onSignal()` → `dedup` → `position check` → `risk check` → `execution`.

**✔ DONE CRITERIA**
- Единый поток исполнения.

---

## 🟢 STAGE 3 EXIT CRITERIA (ОБЯЗАТЕЛЬНЫЕ)
1. 72h testnet: 0 duplicate orders, 0 phantom executions.
2. Position state consistent.
3. Risk engine всегда срабатывает корректно.

---

## 🚀 RESULT STAGE 3
Система = **stable event-driven OMS (production-ready for testnet)**.
