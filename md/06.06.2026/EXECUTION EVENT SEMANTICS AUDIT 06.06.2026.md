EXECUTION EVENT SEMANTICS AUDIT
1. Все места публикации событий
   Сводная таблица публикаций outboxService.publishEvent(...)
#	Файл:строка	Event Type	Кто публикует	При каких условиях/статусах	Payload
1	OrderApplicationService.java:59	ORDER_CREATED	OrderApplicationService.handleSignal()	При создании ордера из сигнала	OrderCreatedEvent(signalId, orderId, executionId)
2	TradeService.java:45	ORDER_FILLED	TradeService.onOrderFilled(OrderFilledEvent)	Внешнее событие от WebSocket (FILL от биржи)	OrderFilledEvent (сырой) — consumer не найден ⚠️
3	TradeService.java:94	TRADE_CREATED	TradeService.onOrderFilled()	После успешного сохранения TradeEntity	TradeCreatedEvent с полными полями + stopLoss/takeProfit из ордера
4	OrderExecutionHandler.java:188	ORDER_EXECUTED	OrderExecutionHandler.commitExecution()	Всегда: SUCCESS, REJECTED, TIMEOUT	OrderExecutedEvent.from(order)
Ключевой факт: События ORDER_REJECTED, ORDER_TIMEOUT, ORDER_FAILED не существуют в кодовой базе как event-типы Outbox. Все три статуса идут через единственный тип ORDER_EXECUTED.

⚠️ FAILED_IO и CANCELED из ExecutionResult.Status вообще не обрабатываются в commitExecution() — ветка if/else (строки 171–183) покрывает только SUCCESS, REJECTED, TIMEOUT. При FAILED_IO/CANCELED управление проваливается в публикацию ORDER_EXECUTED без какого-либо изменения состояния ордера.

2. Проверка инварианта ORDER_EXECUTED
   Желаемый инвариант
   ORDER_EXECUTED ⇒ executedQuantity > 0 ∧ averagePrice ≠ null
   Все места нарушения инварианта
#	Файл:строка	root cause	executedQuantity	averagePrice в Order
1	OrderExecutionHandler.java:178–179	REJECTED → markAsRejected()	null (не выставляется)	null (не выставляется)
2	OrderExecutionHandler.java:180–181	TIMEOUT → markAsUnknown()	null (не выставляется)	null (не выставляется)
3	OrderExecutionHandler.java:96–100	placeOrder() бросает исключение → return на строке 93, минуя commitExecution()	— (не доходит до публикации)	—
4	OrderExecutionHandler.java:183-	FAILED_IO/CANCELED → нет ветки в if/else, ордер не мутирует, остаётся в EXECUTING	null (не было fill)	null (не было fill)
Детали по каждому:

(1) REJECTED — Order.markAsRejected() (Order.java:264–272)

public void markAsRejected(ExecutionContext context, String reason) {
// устанавливает ТОЛЬКО:
this.status = OrderStatus.REJECTED;
this.rejectionReason = reason;
// executedQuantity, averagePrice — НЕ ТРОГАЕТ (остаются null)
}
(2) TIMEOUT — Order.markAsUnknown() (Order.java:284–291)

public void markAsUnknown(ExecutionContext context) {
// устанавливает ТОЛЬКО:
this.status = OrderStatus.UNKNOWN;
// executedQuantity, averagePrice — НЕ ТРОГАЕТ (остаются null)
}
(3) IO Exception — OrderExecutionHandler.java:91–93

} catch (Exception e) {
log.error("[EXECUTION-IO-ERROR] ... Will be recovered by reconciliation.", context, e);
return; // ← commitExecution НЕ вызывается, ORDER_EXECUTED НЕ публикуется
}
Этот путь безопасен — не доходит до публикации.

(4) FAILED_IO / CANCELED — OrderExecutionHandler.java:171–183

if (result.getStatus() == ExecutionResult.Status.SUCCESS) { ... }
else if (result.getStatus() == ExecutionResult.Status.REJECTED) { ... }
else if (result.getStatus() == ExecutionResult.Status.TIMEOUT) { ... }
// FAILED_IO и CANCELED — проваливаются сквозь все условия
// Ордер НЕ мутирует → остаётся в EXECUTING
// Но доходит до строки 188: outboxService.publishEvent("ORDER_EXECUTED", ...)
FAILED_IO/CANCELED → ордер в статусе EXECUTING с executedQuantity=null, averagePrice=null → ORDER_EXECUTED публикуется с пустыми полями → handler на строке 70 не попадает ни в одну ветку (FILLED, PARTIALLY_FILLED, REJECTED не матчатся) → проваливается в executedQty > 0 guard → строит TradeCreatedEvent с price=null → NPE.

3. Обработчики ORDER_EXECUTED и TRADE_CREATED
   3a. OrderExecutedEventHandler (единственный handler для ORDER_EXECUTED)
   Файл: OrderExecutedEventHandler.java:29

Поле из payload	Ожидается non-null?	Реально может быть null?	Защитная проверка?
orderId	Да (строка 46)	Нет (из конструктора Order)	orderPort.findById() → orElseThrow ✅
attempt.executionId	Да (строка 47)	Нет (генерируется)	✅
quantity	Да (строка 78)	Да — это originalQuantity, всегда > 0	compareTo(ZERO) > 0 — не защищает от REJECTED ❌
price	Неявно ожидается	Да — null для REJECTED/TIMEOUT/FAILED_IO/CANCELED	НЕТ проверки перед передачей в TradeCreatedEvent ❌
status	Да (строка 66)	Нет	Частично — только FILLED/PARTIALLY_FILLED/REJECTED. UNKNOWN/EXECUTING/CANCELED — не обрабатываются ❌
rejectionReason	Опционально	Да — null для не-REJECTED	Тернарный оператор с fallback ❓ (частично)
Детальный разбор веток обработки статусов (строки 70–76):

if (targetStatus == OrderStatus.FILLED)          // ✅ обработано
else if (targetStatus == OrderStatus.PARTIALLY_FILLED) // ✅ обработано
else if (targetStatus == OrderStatus.REJECTED)   // ✅ обработано
// UNKNOWN, EXECUTING, CANCELED, ERROR — НЕТ ВЕТОК ❌
Все непокрытые статусы проваливаются мимо if/else прямо в строку 78:

if (executedQty != null && executedQty.compareTo(BigDecimal.ZERO) > 0) {
// Строит TradeCreatedEvent с price=null → NPE
}
3b. PositionProjectionHandler (consumer для TRADE_CREATED)
Файл: PositionProjectionHandler.java:15

Десериализует TradeCreatedEvent → сразу в positionService.updatePosition(tradeEvent)
Никаких проверок на price == null — доверяет тому, что пришло по Outbox
Если price=null пришёл из OrderExecutedEventHandler → NPE в PositionEntity.applyTrade()
3c. EquityProjectionHandler (consumer для TRADE_CREATED)
Файл: EquityProjectionHandler.java:15

Десериализует TradeCreatedEvent → equityService.onTradeCreated(tradeEvent)
Аналогично: никаких проверок на price — потенциальный NPE при расчёте PnL
4. Модель Order: executedQuantity и averagePrice
   Где устанавливается executedQuantity и averagePrice:
   Метод	Файл:строка	executedQuantity	averagePrice	Статус после вызова
   fill()	Order.java:205–206	executedQty ✅	executedPrice ✅	FILLED
   fill() (overload)	Order.java:211–213	делегирует →	делегирует →	FILLED
   forceFill()	Order.java:230–231	executedQty ✅	executedPrice ✅	FILLED
   applyPartialFill()	Order.java:250–251	qty ✅	price ✅	PARTIALLY_FILLED
   reconstruct()	Order.java:142–143	из БД ✅	из БД ✅	из БД
   createPendingExecution()	Order.java:88–98	НЕ устанавливается ❌	НЕ устанавливается ❌	PENDING_EXECUTION
   markExecuting()	Order.java:176–187	НЕ трогает	НЕ трогает	EXECUTING
   markRecovering()	Order.java:255–262	НЕ трогает	НЕ трогает	RECOVERING
   markAsRejected()	Order.java:264–272	НЕ трогает ❌	НЕ трогает ❌	REJECTED
   markCancelled()	Order.java:274–281	НЕ трогает	НЕ трогает	CANCELED
   markAsUnknown()	Order.java:284–291	НЕ трогает ❌	НЕ трогает ❌	UNKNOWN
   Статусы, гарантирующие наличие executedQuantity и averagePrice:
   Статус	executedQuantity	averagePrice	Гарантия
   FILLED	≠ null, > 0	≠ null	✅ Гарантировано — только fill()/forceFill() может перевести в FILLED
   PARTIALLY_FILLED	≠ null, > 0	≠ null	✅ Гарантировано — только applyPartialFill()
   REJECTED	null (если создан через createPendingExecution)	null	❌ Не гарантировано
   UNKNOWN	null	null	❌ Не гарантировано
   EXECUTING	null	null	❌ Не гарантировано
   PENDING_EXECUTION	null (конструктор)	null (конструктор)	❌ Не гарантировано
5. Анализ контракта «статус → событие»
   Предлагаемый контракт
   SUCCESS     → ORDER_EXECUTED   (executedQuantity > 0, averagePrice ≠ null)
   REJECTED    → ORDER_REJECTED   (только reason, без price)
   TIMEOUT     → ORDER_TIMEOUT    (только статус, без price)
   FAILED_IO   → ORDER_FAILED     (ошибка IO, без price)
   CANCELED    → (не публикуем или ORDER_CANCELED)
   Безопасность для существующих consumers
   Consumer OrderExecutedEventHandler (ORDER_EXECUTED)
   Перестанет получать события для REJECTED/TIMEOUT/FAILED_IO/CANCELED — это хорошо, исчезнет NPE
   Нужно создать новые handler'ы для ORDER_REJECTED, ORDER_TIMEOUT, ORDER_FAILED:
   ORDER_REJECTED handler: только markAsRejected() + логирование/уведомление, без создания TradeCreatedEvent
   ORDER_TIMEOUT handler: только markAsUnknown() + логирование, без TradeCreatedEvent
   ORDER_FAILED handler: логирование + возможно reconciliation
   Consumer PositionProjectionHandler (TRADE_CREATED)
   Не затрагивается — раз «плохой» TradeCreatedEvent больше не создаётся, problem solved
   TradeService.onOrderFilled() продолжит публиковать корректные TRADE_CREATED через свой путь
   Consumer EquityProjectionHandler (TRADE_CREATED)
   Не затрагивается — аналогично
   Потенциально сломаются:
#	Что сломается	Причина	Как исправить
1	Ничего напрямую — события ORDER_REJECTED/ORDER_TIMEOUT/ORDER_FAILED не существуют как строки Outbox, никто на них не подписан	Старый handler просто перестанет получать «плохие» события	Добавить новые handler'ы для новых event-типов
2	Мониторинг/алертинг (если завязан на ORDER_EXECUTED как индикатор «любого завершения»)	REJECTED/TIMEOUT больше не будут counted как EXECUTED	Перенастроить мониторинг на сумму ORDER_EXECUTED + ORDER_REJECTED + ORDER_TIMEOUT
3	Reconciliation, если он ожидает ORDER_EXECUTED для REJECTED/TIMEOUT ордеров	Статус-машина ожидает конкретный event	Reconciliation работает через статусы Order'а, не через события — скорее всего не сломается
❌ Consumer'ы, которые сломаются после изменения: 0
Текущих потребителей ORDER_REJECTED/ORDER_TIMEOUT/ORDER_FAILED не существует. Единственный потребитель ORDER_EXECUTED (OrderExecutedEventHandler) станет только безопаснее — перестанет получать события с price=null.

6. Таблица: Status × Event × executedQuantity × averagePrice
   ExecutionResult.Status	OrderStatus после commit	Событие (сейчас)	executedQuantity в Order	averagePrice в Order	NPE risk
   SUCCESS	FILLED	ORDER_EXECUTED	result.getExecutedQty() ≠ null ✅	result.getExecutedPrice() ≠ null ✅	Нет
   SUCCESS (partial)	PARTIALLY_FILLED	ORDER_EXECUTED	result.getExecutedQty() ≠ null ✅	result.getExecutedPrice() ≠ null ✅	Нет
   REJECTED	REJECTED	ORDER_EXECUTED	null ❌	null ❌	ДА
   TIMEOUT	UNKNOWN	ORDER_EXECUTED	null ❌	null ❌	ДА
   FAILED_IO	не меняется (EXECUTING)	ORDER_EXECUTED	null ❌	null ❌	ДА
   CANCELED	не меняется (EXECUTING)	ORDER_EXECUTED	null ❌	null ❌	ДА
   IO Exception (строка 93)	не меняется	не публикуется	—	—	Нет (не доходит)
   Итоговый вывод
   NPE — не случайный баг, а структурный дефект событийной модели
   Корневая проблема: ORDER_EXECUTED семантически означает «исполнение завершено», но используется как «любой финальный статус». Из 6 возможных статусов ExecutionResult только 1 (SUCCESS) реально имеет executedQuantity > 0 ∧ averagePrice ≠ null. Остальные 5 либо проваливаются с NPE, либо не доходят до публикации.

Что нужно исправить (в порядке приоритета):
Разделить ORDER_EXECUTED на отдельные event-типы в commitExecution() (строка 188–193): ORDER_EXECUTED только для SUCCESS, новые типы для REJECTED/TIMEOUT/FAILED_IO/CANCELED.

Добавить обработчики для новых event-типов — OrderRejectedEventHandler, OrderTimeoutEventHandler, OrderFailedEventHandler — которые не создают TradeCreatedEvent.

Добавить защитный guard в PositionEntity.applyTrade(): if (tradePrice == null) throw ... — последний рубеж обороны.

Исправить OrderExecutedEvent.from(): строка 42 — заменить order.getQuantity() на order.getExecutedQuantity().

Добавить обработку UNKNOWN и EXECUTING в handler (строки 70–76) — даже если п.1 не сделан, избежать silent fallthrough.