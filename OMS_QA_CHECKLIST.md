OMS / TRADING ENGINE QA CHECKLIST (Stage 3 → Stage 4 GATE)
🎯 Цель

Подтвердить ключевой инвариант системы:

1 Signal → 1 Risk Decision → 1 Order → 1 Position

Гарантированно при:

concurrency
restart
retry
duplicate delivery
async execution
🟢 1. BASIC FLOW (End-to-End)
ID	Тест	Ожидаемый результат	Статус
TC-1	BUY signal end-to-end	SignalEvent → OMS → Risk APPROVED → Execution FILLED → Position OPEN. Ровно 1 order и 1 execution
TC-2	SELL after BUY	Позиция корректно закрывается или переворачивается. Нет двойной позиции
TC-3	HOLD signal	Полный no-op. OMS не вызывается, ордера не создаются
🟡 2. IDEMPOTENCY (NO DUPLICATES GUARANTEE)
ID	Тест	Ожидаемый результат	Статус
TC-4	Duplicate SignalEvent replay	Второй сигнал полностью игнорируется (log: duplicate detected)
TC-5	Restart + resend signal	Ордер не создаётся повторно (DB idempotency via clientOrderId)
TC-6	Duplicate candle → duplicate signal	Только один сигнал обрабатывается
🔴 3. CONCURRENCY / RACE CONDITIONS
ID	Тест	Ожидаемый результат	Статус
TC-7	Parallel BUY + SELL (same symbol)	Только один проходит OMS pipeline, второй блокируется guard/risk
TC-8	Burst load (10–20 signals/sec)	Нет дублей, нет corruption состояния, система стабильна
🟣 4. POSITION CONSISTENCY
ID	Тест	Ожидаемый результат	Статус
TC-9	Same-direction BUY on open BUY	Игнорируется или отклоняется согласно стратегии (no duplicate exposure)
TC-10	Flip BUY → SELL → BUY	Всегда 1 активная позиция на символ
🔵 5. RISK ENGINE VALIDATION
ID	Тест	Ожидаемый результат	Статус
TC-11	Risk rejection	Ордер не создаётся. Log: rejected by risk gate
TC-12	Stale approval	Execution блокируется при устаревшем approval
⚫ 6. FAILURE / RESILIENCE
ID	Тест	Ожидаемый результат	Статус
TC-13	Execution failure (exchange error)	Order = ERROR/REJECTED, система продолжает работу
TC-14	DB failure during persist	Полный rollback, нет частично записанных состояний
🧠 7. SYSTEM INVARIANT (HARD CONTRACT)
1 signal → максимум 1 order
1 order → максимум 1 execution
Position state = execution history (no drift)
Restart НЕ создаёт новых сделок
Risk engine — обязательный first gate
📊 EXIT CRITERIA (GATE TO STAGE 4)

Переход разрешён только при выполнении всех условий:

✅ Stability (72h testnet)
0 duplicate orders
0 phantom executions
0 inconsistent states
✅ Consistency
Position state полностью соответствует trade history
Нет расхождений после restart/rebuild
✅ Concurrency Safety
Нет race condition логов
Нет параллельного двойного execution одного события