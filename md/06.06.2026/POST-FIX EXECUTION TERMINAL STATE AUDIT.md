POST-FIX EXECUTION TERMINAL STATE AUDIT
1. Что сохраняется в БД после commitExecution() для каждого статуса
   ExecutionResult.Status	OrderStatus в БД	Мутирующий метод	Event Type
   SUCCESS	FILLED	order.fill() → строка 206: this.status = FILLED	ORDER_EXECUTED
   REJECTED	REJECTED	order.markAsRejected() → строка 270: this.status = REJECTED	ORDER_REJECTED
   TIMEOUT	UNKNOWN	order.markAsUnknown() → строка 290: this.status = UNKNOWN	ORDER_TIMEOUT
   FAILED_IO	EXECUTING 🔴	НЕ МУТИРУЕТСЯ — только лог на строках 183-185	ORDER_FAILED
   CANCELED	EXECUTING 🔴	НЕ МУТИРУЕТСЯ — только лог на строках 186-188	ORDER_CANCELED
2. Может ли ордер остаться в EXECUTING после commitExecution()?
   Да — в двух случаях:

#	Статус	Строки	Причина
1	FAILED_IO	OrderExecutionHandler.java:183-188	Ветка else if не вызывает ни один мутирующий метод. Только log.error(...).
2	CANCELED	OrderExecutionHandler.java:186-188	Аналогично — только log.info(...).
// OrderExecutionHandler.java:183-188
} else if (result.getStatus() == ExecutionResult.Status.FAILED_IO) {
log.error("[EXECUTION-FAILED-IO] IO error during execution. Order remains in {}. ...",
order.getStatus(), context, result.getErrorMessage());
} else if (result.getStatus() == ExecutionResult.Status.CANCELED) {
log.info("[EXECUTION-CANCELED] Order execution canceled. Order remains in {}. ...",
order.getStatus(), context);
}
После этих веток ордер сохраняется в БД (orderRepository.save(order) на строке 191) с тем же статусом EXECUTING и публикуется Outbox-событие.

3. Кто отвечает за дальнейший переход состояния
   ExecutionResult.Status	Кто переводит дальше	Таймаут	Механизм
   SUCCESS	commitExecution()	—	Done.
   REJECTED	commitExecution()	—	Done.
   TIMEOUT	commitExecution() → UNKNOWN → Reconciliation	—	Переводит в UNKNOWN сразу.
   FAILED_IO	ReconciliationService или OrderWatchdogService	30s (stale) / 2min (stuck)	Watchdog каждые 60s ищет stuck EXECUTING >2min. Reconciliation каждые 5min ищет reconcilable статусы
   CANCELED	ReconciliationService или OrderWatchdogService	30s (stale) / 2min (stuck)	То же самое
4. Может ли ордер остаться EXECUTING навсегда?
   Теоретически — нет. Практически — зависит от работоспособности scheduled jobs.

Механизмы спасения:
#	Механизм	Расписание	Условие срабатывания	Что делает
1	OrderWatchdogService.checkStuckOrders()	@Scheduled 60s	OrderStatus.EXECUTING AND executionStartedAt < now - 2min	Вызывает reconciliationService.reconcile(context)
2	ReconciliationService.reconcilePendingOrders()	@Scheduled 5min	isReconcilable(status) — EXECUTING включён	Вызывает syncOrderWithExchange()
3	OrderStateTransitionPolicy.isStale()	— (проверка в рантайме)	EXECUTING AND startedAt < now - 30s	Разрешает перезахват ордера для reconciliation
Условия, при которых ордер может застрять:
❌ Watchdog/Reconciliation stopped/suspended
❌ Биржа недоступна для запроса в syncOrderWithExchange()
❌ FAILED_IO / CANCELED недокументированы в syncOrderWithExchange() — reconciliation тоже может не знать, что делать
Но в нормальной работе: через 2 минуты Watchdog подхватит и отправит в reconciliation.

5. Все места чтения OrderStatus.EXECUTING
#	Файл	Строка	Контекст	Что делает
1	Order.java	180	markExecuting() — idempotency guard	Если уже EXECUTING → NOOP
2	OrderRepositoryAdapter.java	80	claimForReconciliation()	Если EXECUTING + not stale → отказ в захвате
3	OrderRepositoryAdapter.java	43	claimForExecutionInCurrentTransaction()	Если EXECUTING + not stale → отказ
4	OrderStateTransitionPolicy.java	96	requestTransition() — guard	UNKNOWN разрешён только из EXECUTING
5	OrderStateTransitionPolicy.java	133	isStale()	Только EXECUTING считается stale (>30s)
6	OrderStateTransitionPolicy.java	151	getReconcilableStatuses()	EXECUTING в списке 6 reconcilable статусов
7	OrderWatchdogService.java	39	findStuckOrders(EXECUTING, threshold)	Поиск stuck ордеров
8	ExecutionStateMapper.java	16	toContractState()	Маппинг EXECUTING → "EXECUTING" строка
9	OrderExecutedEventHandler.java	70-78	consume() switch	EXECUTING не обрабатывается явно — fallthrough
6. Все места перехода ИЗ EXECUTING
   Полная таблица переходов
#	Source	Target	Метод	Файл	Строки	Контекст
1	EXECUTING	EXECUTING	markExecuting()	Order.java	176-187	Idempotent NOOP — само-переход
2	EXECUTING	FILLED	fill()	Order.java	189-208	SUCCESS — нормальное исполнение
3	EXECUTING	FILLED	forceFill()	Order.java	220-233	Reconciliation force-fill
4	EXECUTING	PARTIALLY_FILLED	applyPartialFill()	Order.java	235-253	Частичное исполнение
5	EXECUTING	REJECTED	markAsRejected()	Order.java	264-272	REJECTED от биржи
6	EXECUTING	REJECTED	markAsRejected()	ReconciliationService.java	207-211	Reconciliation — rejected
7	EXECUTING	UNKNOWN	markAsUnknown()	Order.java	284-291	TIMEOUT исполнения
8	EXECUTING	CANCELED	markCancelled()	Order.java	274-281	CANCELED
9	EXECUTING	CANCELED	markCancelled()	ReconciliationService.java	212	Reconciliation — cancelled
10	EXECUTING	RECOVERING	markRecovering()	Order.java	255-262	NOT directly from EXECUTING — requestTransition() разрешает только PENDING_EXECUTION→RECOVERING
Разрешённые переходы из EXECUTING (OrderStateTransitionPolicy)
// OrderStateTransitionPolicy.java:36-44
STATE_GRAPH.put(OrderStatus.EXECUTING, Set.of(
OrderStatus.EXECUTING,         // self
OrderStatus.SENT_TO_EXCHANGE,
OrderStatus.FILLED,
OrderStatus.PARTIALLY_FILLED,
OrderStatus.REJECTED,
OrderStatus.UNKNOWN,
OrderStatus.CANCELED
));
⚠️ RECOVERING НЕ разрешён из EXECUTING. Граф переходов показывает: UNKNOWN → RECOVERING, но не EXECUTING → RECOVERING.

7. Детали по каждому переходу
   7.1 EXECUTING → FILLED (нормальный путь)
   Аспект	Деталь
   source	EXECUTING
   target	FILLED
   метод	Order.fill(ExecutionContext, String exchangeOrderId, BigDecimal executedQty, BigDecimal executedPrice)
   файл	Order.java
   строки	189–208
   вызывается из	OrderExecutionHandler.commitExecution():171-177
   проверка	validateAndPassThrough(context, this.status, FILLED)
   7.2 EXECUTING → FILLED (force fill через reconciliation)
   Аспект	Деталь
   source	EXECUTING
   target	FILLED
   метод	Order.forceFill(ExecutionContext, String, BigDecimal, BigDecimal)
   файл	Order.java
   строки	220–233
   вызывается из	ReconciliationService.syncOrderWithExchange():193
   особенность	Пропускает lastAppliedExecutionId guard
   7.3 EXECUTING → REJECTED (через commitExecution)
   Аспект	Деталь
   source	EXECUTING
   target	REJECTED
   метод	Order.markAsRejected(ExecutionContext, String reason)
   файл	Order.java
   строки	264–272
   вызывается из	OrderExecutionHandler.commitExecution():178-179
   7.4 EXECUTING → REJECTED (через reconciliation)
   Аспект	Деталь
   source	EXECUTING
   target	REJECTED
   метод	Order.markAsRejected()
   файл	ReconciliationService.java
   строки	207-211
   7.5 EXECUTING → UNKNOWN (таймаут)
   Аспект	Деталь
   source	EXECUTING
   target	UNKNOWN
   метод	Order.markAsUnknown(ExecutionContext)
   файл	Order.java
   строки	284–291
   вызывается из	OrderExecutionHandler.commitExecution():180-181
   guard	requestTransition() — строка 96: UNKNOWN разрешён только из EXECUTING ✅
   7.6 EXECUTING → CANCELED (через commitExecution)
   Аспект	Деталь
   source	EXECUTING
   target	CANCELED
   метод	Order.markCancelled(ExecutionContext)
   файл	Order.java
   строки	274–281
   вызывается из	OrderExecutionHandler.commitExecution() — НЕ ВЫЗЫВАЕТСЯ 🔴
   примечание	Ветка CANCELED не вызывает markCancelled(). Только reconciliation вызывает.
8. Итоговая схема EXECUTING state machine
   PENDING_EXECUTION
   │
   ▼ markExecuting()
   EXECUTING
   │
   ┌──────────────────────┼──────────────────────┐
   │                      │                      │
   [SUCCESS]              [REJECTED]             [TIMEOUT]
   fill()                 markAsRejected()       markAsUnknown()
   │                      │                      │
   ▼                      ▼                      ▼
   FILLED                REJECTED               UNKNOWN
   (terminal)            (terminal)                  │
   [reconcil]
   │
   ┌───┴───┐
   ▼       ▼
   FILLED  REJECTED

┌──────────────────────┐
│                      │
[FAILED_IO]            [CANCELED]
❌ нет мутации         ❌ нет мутации
│                      │
▼                      ▼
EXECUTING              EXECUTING
(остаётся)             (остаётся)
│                      │
┌────┴────┐            ┌────┴────┐
│ Watchdog│            │ Watchdog│
│ 2 min   │            │ 2 min   │
└────┬────┘            └────┬────┘
│                      │
▼                      ▼
Reconciliation        Reconciliation
syncOrderWithExchange syncOrderWithExchange
│                      │
┌────┼────┐            ┌────┼────┐
▼    ▼    ▼            ▼    ▼    ▼
FILLED REJ CANC        FILLED REJ CANC
Обнаруженная проблема:
#	Severity	Описание
1	🟠 HIGH	FAILED_IO / CANCELED не мутируют ордер в commitExecution() — ордер остаётся EXECUTING, полагаясь на Watchdog/Reconciliation для выхода. Между commit и Watchdog — до 2 минут ордер висит в EXECUTING.
2	🟡 MEDIUM	markAsError() / markCancelled() существуют в графе переходов, но commitExecution() их не вызывает. Код написан так, будто эти статусы будут обработаны «позже».
3	🟢 LOW	RECOVERING не разрешён из EXECUTING в графе переходов — ордер должен сначала перейти в UNKNOWN. Это корректно для текущей архитектуры, но стоит задокументировать.