1. Полный enum OrderStatus
   Файл: OrderStatus.java‎
   , строки 6–20

public enum OrderStatus {
NEW,                // Создан в системе
VALIDATED,          // Прошел проверку риск-менеджмента
PENDING_EXECUTION,  // Ожидает асинхронного исполнения
EXECUTING,          // В процессе исполнения (атомарный захват)
SENT,               // Отправлен на биржу
SENT_TO_EXCHANGE,   // Отправлен в шлюз биржи (ожидание подтверждения)
PARTIALLY_FILLED,   // Частично исполнен
FILLED,             // Полностью исполнен
CANCELED,           // Отменен пользователем или биржей
REJECTED,           // Отклонен риск-менеджментом или биржей
UNKNOWN,            // Результат обмена неизвестен (timeout / ambiguous)
RECOVERING,         // В процессе восстановления состояния (reconciliation)
ERROR               // Критическая ошибка при обработке
}
Терминальные статусы (в state graph — пустые переходы)
Статус	Файл	Строка	Доказательство
FILLED	OrderStateTransitionPolicy.java	78	Collections.emptySet()
REJECTED	OrderStateTransitionPolicy.java	79	Collections.emptySet()
CANCELED	OrderStateTransitionPolicy.java	80	Collections.emptySet()
ERROR	OrderStateTransitionPolicy.java	81	Collections.emptySet()
2. Семантика статусов
   Вопрос	Ответ	Статусы
   Ордер размещён (в системе)	Системный факт создания	NEW
   Ордер размещён (на бирже), живёт	Биржа приняла, не исполнен	SENT_TO_EXCHANGE ⚠️ (не используется)
   Ордер ожидает исполнения	Готов к отправке, но не взят в работу	PENDING_EXECUTION
   Ордер в процессе исполнения	Атомарный захват, отправка на биржу	EXECUTING
   Ордер можно реконсилировать	Не-терминальные статусы	PENDING_EXECUTION, EXECUTING, SENT_TO_EXCHANGE, PARTIALLY_FILLED, UNKNOWN, RECOVERING
   Состояние неизвестно	Таймаут/ambiguous	UNKNOWN
   Ключевое открытие
   🔴 SENT_TO_EXCHANGE — определён в enum и state graph, но НИГДЕ не используется в production-коде. Нет метода markSent() или аналогичного. Статус существует как «призрак» — граф переходов его знает, реконсиляция его включает, но ни один producer никогда не переводит ордер в это состояние.

3. Все места использования SENT_TO_EXCHANGE, EXECUTING, UNKNOWN
   SENT_TO_EXCHANGE — 4 места, все read-only
#	Файл	Строка	Тип	Что делает
1	OrderStatus.java	12	Определение	SENT_TO_EXCHANGE, // Отправлен в шлюз биржи (ожидание подтверждения)
2	OrderStateTransitionPolicy.java	38	State graph	EXECUTING → SENT_TO_EXCHANGE
3	OrderStateTransitionPolicy.java	47	State graph	SENT_TO_EXCHANGE → {FILLED, PARTIALLY_FILLED, REJECTED, CANCELED}
4	OrderStateTransitionPolicy.java	151	Reconcilable	Включён в getReconcilableStatuses()
Ни одного producer-вызова. Статус мёртв.

EXECUTING — 15 мест
#	Файл	Строка	Тип	Что делает
1	OrderStatus.java	10	Определение	Enum член
2	Order.java	176–186	Producer	markExecuting() — PENDING_EXECUTION → EXECUTING
3	OrderRepositoryAdapter.java	56	Вызов	order.markExecuting(context) при claim
4	OrderRepositoryAdapter.java	80	Проверка	isExecuting для блокировки reconciliation
5	OrderStateTransitionPolicy.java	35–44	State graph	Переходы из EXECUTING
6	OrderStateTransitionPolicy.java	96	Guard	UNKNOWN только из EXECUTING
7	OrderStateTransitionPolicy.java	133	Stale	isStale() смотрит только на EXECUTING
8	OrderStateTransitionPolicy.java	151	Reconcilable	Включён
9	OrderWatchdogService.java	39	Watchdog	Ищет stuck ордера в EXECUTING
10	ExecutionStateMapper.java	16	Mapping	EXECUTING → "EXECUTING"
11	ExecutionLockRepository.java	23,27,34	Lock state	state = 'EXECUTING'
UNKNOWN — 9 мест
#	Файл	Строка	Тип	Что делает
1	OrderStatus.java	17	Определение	Enum член
2	Order.java	284–291	Producer	markAsUnknown() — TIMEOUT → UNKNOWN
3	OrderExecutionHandler.java	181	Вызов	order.markAsUnknown(context) при TIMEOUT
4	OrderStateTransitionPolicy.java	42	State graph	Из EXECUTING
5	OrderStateTransitionPolicy.java	62–68	State graph	UNKNOWN → {FILLED, REJECTED, EXECUTING, RECOVERING}
6	OrderStateTransitionPolicy.java	75	State graph	Из RECOVERING
7	OrderStateTransitionPolicy.java	96	Guard	Только из EXECUTING
8	OrderStateTransitionPolicy.java	123	Mapping	TIMEOUT → UNKNOWN
9	OrderStateTransitionPolicy.java	151	Reconcilable	Включён
10	ReconciliationService.java	185	Recovery	UNKNOWN → RECOVERING
4. Фактический путь: Signal → Binance NEW
   Пошаговая трассировка
   ШАГ 1: Signal получен
   └─ RiskEngine.evaluateSignal() → Order.createPendingExecution()
   └─ OrderStatus.PENDING_EXECUTION
   (Order.java:97)

ШАГ 2: Outbox consumer получает ORDER_CREATED
└─ OrderExecutionHandler.consume() (строка 57)
└─ claimOrder() (строка 118)
└─ OrderRepositoryAdapter.claimForExecution() (строка 66)
└─ order.markExecuting(context) (строка 56)
└─ OrderStatus.EXECUTING
(Order.java:186)

ШАГ 3: executionPort.placeOrder(order) (строка 90)
└─ BinanceExecutionAdapter.doPlaceOrder() (строка 42)
└─ binanceClient.post("/api/v3/order", ...) (строка 57)
└─ Binance API возвращает: { status: "NEW", orderId: 12345, executedQty: "0" }

ШАГ 4: mapToExecutionResult(order, response) (строка 58)
└─ строка 153: boolean success = "FILLED".equals(status)
|| "NEW".equals(status)        // ← TRUE!
|| "PARTIALLY_FILLED".equals(status)
└─ строка 155-161:
ExecutionResult.builder()
.status(ExecutionResult.Status.SUCCESS)   // ← SUCCESS!
.executedQty(BigDecimal("0"))
.build()

ШАГ 5: OrderExecutionHandler.commitExecution() (строка 157)
└─ строка 171: if (result.getStatus() == ExecutionResult.Status.SUCCESS)
└─ order.fill(context, exchangeOrderId, executedQty, executedPrice) (строка 172)
└─ Order.java:207 → this.status = OrderStatus.FILLED  // 🔴 FILLED при NEW!
Цепочка статусов
PENDING_EXECUTION → EXECUTING → (Binance NEW) → SUCCESS → FILLED
↑
🔴 ОРДЕР НЕ ИСПОЛНЕН!
Факт: ордер существует на бирже в статусе NEW, executedQty = "0", но в системе помечен как FILLED.

5. Какой OrderStatus должен быть после Binance NEW?
   Варианты анализа
   Вариант	State graph разрешает?	Семантически верно?	Используется сегодня?
   EXECUTING	✅ Да (само-переход)	⚠️ Частично — ордер уже отправлен	Да
   SENT_TO_EXCHANGE	✅ Да (строка 38)	✅ Да — «ордер живёт на бирже»	❌ Нет
   UNKNOWN	❌ Нет (только из EXECUTING + только TIMEOUT)	❌ Нет — состояние известно	Н/Д
   FILLED	✅ Да (строка 39)	❌ Нет — ордер не исполнен	🔴 Да (баг)
   Ответ: SENT_TO_EXCHANGE
   Обоснование через state graph:

Определение статуса (строка 12): SENT_TO_EXCHANGE — Отправлен в шлюз биржи (ожидание подтверждения). Это точное описание ситуации «Binance вернул NEW» — ордер принят биржей, ждёт исполнения.

State graph (строка 36–38): EXECUTING → SENT_TO_EXCHANGE — легальный переход. Означает: «ордер захвачен, отправлен на биржу, биржа подтвердила приём».

State graph из SENT_TO_EXCHANGE (строка 47–51):

SENT_TO_EXCHANGE → FILLED
→ PARTIALLY_FILLED
→ REJECTED
→ CANCELED
Отсюда — корректные пути: ордер может исполниться (FILLED), частично исполниться (PARTIALLY_FILLED), быть отклонён (REJECTED) или отменён (CANCELED). Все пути ведут в валидные терминальные состояния.

Reconcilable (строка 151): SENT_TO_EXCHANGE включён в getReconcilableStatuses() — реконсиляция может проверить статус такого ордера на бирже и перевести его в FILLED/PARTIALLY_FILLED/REJECTED/CANCELED.

В отличие от FILLED: FILLED — терминальный статус (строка 78: Collections.emptySet()), из него нет переходов. Попадание в FILLED для неисполненного ордера — тупик, который нельзя исправить штатным путём.

В отличие от EXECUTING: EXECUTING семантически означает «в процессе отправки», а не «отправлено и принято». Оставлять ордер в EXECUTING после успешной отправки — терять информацию о том, что биржа его приняла.

Что нужно для реализации
Добавить метод markSentToExchange(context, exchangeOrderId) в Order.java:

public void markSentToExchange(ExecutionContext context, String exchangeOrderId) {
OrderStateTransitionPolicy.validateAndPassThrough(context, this.status, OrderStatus.SENT_TO_EXCHANGE);
this.exchangeOrderId = exchangeOrderId;
this.status = OrderStatus.SENT_TO_EXCHANGE;
}
Изменить BinanceExecutionAdapter.mapToExecutionResult() — НЕ включать NEW и PARTIALLY_FILLED в SUCCESS:

// строка 153: убрать "NEW" и "PARTIALLY_FILLED"
boolean success = "FILLED".equals(status);
boolean accepted = "NEW".equals(status) || "PARTIALLY_FILLED".equals(status);
Добавить ACCEPTED (или OPEN) в ExecutionResult.Status для маппинга NEW → ACCEPTED

В commitExecution(): ACCEPTED → order.markSentToExchange()

В mapStatusResponse(): NEW → ACCEPTED (а не REJECTED)

Итоговая целевая цепочка
PENDING_EXECUTION → EXECUTING → (Binance NEW) → ACCEPTED → SENT_TO_EXCHANGE
↓
(watchdog/recon) → FILLED/REJECTED/...