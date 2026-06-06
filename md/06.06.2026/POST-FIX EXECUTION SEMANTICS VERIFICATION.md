POST-FIX EXECUTION SEMANTICS VERIFICATION
INVARIANT 1: ORDER_EXECUTED с не-null полями
Единственное место публикации ORDER_EXECUTED:

Файл	Строки	Условие
OrderExecutionHandler.java	205–213	result.getStatus() == SUCCESS && order.getExecutedQuantity() != null && order.getAveragePrice() != null
// resolveCompletionEventType(), строки 205–213
if (result.getStatus() == ExecutionResult.Status.SUCCESS) {
boolean hasRealExecution = order.getExecutedQuantity() != null
&& order.getAveragePrice() != null;                    // ← двойная проверка
if (!hasRealExecution) {
log.error("[INVARIANT-VIOLATION] SUCCESS result but no execution data...");
}
return hasRealExecution ? "ORDER_EXECUTED" : "ORDER_COMPLETED"; // ← ORDER_EXECUTED только при true
}
Ответ: ❌ Нет, ORDER_EXECUTED не может быть опубликован с null-quantity или null-price. Если поля null — публикуется ORDER_COMPLETED (без consumer'а, с логом о нарушении инварианта).

INVARIANT 1 — ✅ PASS

INVARIANT 2: REJECTED/TIMEOUT/FAILED_IO/CANCELED → TradeCreatedEvent
ExecutionResult.Status	OrderStatus после commit	Опубликованное событие	Consumer	TradeCreatedEvent?
SUCCESS (qty≠null, price≠null)	FILLED	ORDER_EXECUTED	OrderExecutedEventHandler	Да ✅
SUCCESS (qty/price null)	FILLED	ORDER_COMPLETED	НЕТ	Нет
REJECTED	REJECTED	ORDER_REJECTED	НЕТ	Нет ✅
TIMEOUT	UNKNOWN	ORDER_TIMEOUT	НЕТ	Нет ✅
FAILED_IO	не меняется (EXECUTING)	ORDER_FAILED	НЕТ	Нет ✅
CANCELED	не меняется (EXECUTING)	ORDER_CANCELED	НЕТ	Нет ✅
Доказательство: OrderExecutedEventHandler.supports() (строка 38) матчит только "ORDER_EXECUTED". Остальные 5 event-типов не имеют consumer'а → OutboxEventRouter пишет warn → TradeCreatedEvent не создаётся.

INVARIANT 2 — ✅ PASS

INVARIANT 3: TradeCreatedEvent без null-полей
Место 1: OrderExecutedEventHandler.java:80–96
// строка 80-81: тройной guard
if (executedQty != null && executedQty.compareTo(BigDecimal.ZERO) > 0
&& executionPrice != null) {
// строка 84-96: создание TradeCreatedEvent
new TradeCreatedEvent(..., executedQty, executionPrice, ...);
positionService.updatePosition(tradeEvent);
}
Защита: executedQty ≠ null ∧ executedQty > 0 ∧ executionPrice ≠ null ✅

Место 2: TradeService.java:79–92
// строки 79-92
TradeCreatedEvent tradeCreatedEvent = new TradeCreatedEvent(
...
saved.getQuantity(),   // ← из TradeEntity (строка 65: event.getQuantity())
saved.getPrice(),      // ← из TradeEntity (строка 66: event.getPrice())
...
);
Защита: нет явного guard'а перед созданием. Поля берутся из TradeEntity, которые заполнены из OrderFilledEvent. Если внешнее событие содержит null — TradeEntity сохранится с null → TradeCreatedEvent с null.

⚠️ Риск: Path через TradeService.onOrderFilled() не имеет guard'а на null поля в OrderFilledEvent. Но PositionEntity.applyTrade() теперь выбрасывает InvalidTradeDataException вместо NPE — ошибка становится видимой, а не тихой.

INVARIANT 3 — ⚠️ PASS (с оговоркой)

Существует edge-case через TradeService, но он вне scope ORDER_EXECUTED fix. Ошибка теперь surfacing, а не silent.

INVARIANT 4: PositionEntity.applyTrade с null
Цепочка A (ORDER_EXECUTED → PositionEntity)
OrderExecutionHandler.commitExecution()              // строки 171-177: fill
→ publishCompletionEvent()                        // строка 193
→ resolveCompletionEventType()                // строка 207-208: проверка qty≠null && price≠null
→ "ORDER_EXECUTED"                        // строка 213
→ outboxService.publishEvent()
→ OrderExecutedEventHandler.consume()                // supports("ORDER_EXECUTED")
→ guard: executedQty≠null && >0 && price≠null    // строки 80-81
→ TradeCreatedEvent(qty, price)               // строки 84-96
→ positionService.updatePosition(tradeEvent)  // строка 97
→ PositionService.updatePosition()         // строка 82
→ entity.applyTrade(qty, price)        // PositionEntity строка 68
Защита на трёх уровнях перед applyTrade:

resolveCompletionEventType() — qty/price проверены
OrderExecutedEventHandler guard — qty/price проверены
PositionEntity.applyTrade() — InvalidTradeDataException для null
Цепочка B (TradeService → PositionEntity)
TradeService.onOrderFilled(OrderFilledEvent)         // строки 35-113
→ TradeEntity.setQuantity(event.getQuantity())    // строка 65
→ TradeEntity.setPrice(event.getPrice())          // строка 66
→ TradeCreatedEvent(quantity, price)              // строки 79-92
→ outboxService.publishEvent(TRADE_CREATED)
→ PositionProjectionHandler.consume()                // строка 26
→ positionService.updatePosition(tradeEvent)      // строка 32
→ PositionService.updatePosition()             // строка 82
→ entity.applyTrade(qty, price)            // PositionEntity строка 68
Защита только на уровне PositionEntity.applyTrade() (строка 69-73): InvalidTradeDataException.

INVARIANT 4 — ✅ PASS (оба пути защищены)

INVARIANT 5: OrderExecutedEvent — фактические данные исполнения
// OrderExecutedEvent.java:42-43
order.getExecutedQuantity(),  // ← реально исполненное количество (может быть null)
order.getAveragePrice(),      // ← средняя цена (может быть null)
Поле payload	Источник	Что это
quantity	order.getExecutedQuantity()	Фактически исполненное количество ✅
price	order.getAveragePrice()	Средняя цена исполнения ✅
order.getQuantity() (= originalQuantity) НЕ используется. ✅

Подтверждение из Order.java: строка 299 — getQuantity() возвращает originalQuantity. В OrderExecutedEvent.from() этого вызова нет.

INVARIANT 5 — ✅ PASS

INVARIANT 6: Switch ветки по OrderStatus в handler
Файл: OrderExecutedEventHandler.java:70–78

if (targetStatus == OrderStatus.FILLED) {                    // ✅ обработано
order.fill(context, executedQty, executionPrice);
} else if (targetStatus == OrderStatus.PARTIALLY_FILLED) {   // ✅ обработано
order.applyPartialFill(context, executedQty, executionPrice);
} else if (targetStatus == OrderStatus.REJECTED) {           // ✅ обработано
order.markAsRejected(context, ...);
} else if (targetStatus == OrderStatus.UNKNOWN) {            // ✅ обработано
order.markAsUnknown(context);
}
// EXECUTING, CANCELED, ERROR, NEW, ... → fallthrough
Статус	Обработан?	Что происходит при fallthrough
FILLED	✅	order.fill()
PARTIALLY_FILLED	✅	order.applyPartialFill()
REJECTED	✅	order.markAsRejected()
UNKNOWN	✅	order.markAsUnknown()
EXECUTING	❌	Проваливается в guard строки 80 — если qty≠null && >0 && price≠null → создаётся TradeCreatedEvent (но для EXECUTING это маловероятно)
CANCELED	❌	То же самое
ERROR	❌	То же самое
⚠️ Edge case: Если по какой-то причине ORDER_EXECUTED опубликован для статуса EXECUTING — handler не мутирует ордер (нет order.markExecuting?), но guard 80-81 может создать TradeCreatedEvent. Однако этого не случится, т.к. ORDER_EXECUTED публикуется только при getExecutedQuantity()≠null ∧ getAveragePrice()≠null (INVARIANT 1), а EXECUTING статус с этими полями — невозможная комбинация.

INVARIANT 6 — ✅ PASS (нет достижимых опасных fallthrough)

INVARIANT 7: Event Type → Publisher → Consumer
Event Type	Publisher	Consumer
ORDER_CREATED	OrderApplicationService:59	OrderExecutionHandler:53
ORDER_EXECUTED	OrderExecutionHandler.resolveCompletionEventType():213	OrderExecutedEventHandler:38
ORDER_REJECTED	OrderExecutionHandler.resolveCompletionEventType():216	НЕТ ⚠️
ORDER_TIMEOUT	OrderExecutionHandler.resolveCompletionEventType():217	НЕТ ⚠️
ORDER_FAILED	OrderExecutionHandler.resolveCompletionEventType():218	НЕТ ⚠️
ORDER_CANCELED	OrderExecutionHandler.resolveCompletionEventType():219	НЕТ ⚠️
ORDER_COMPLETED	OrderExecutionHandler.resolveCompletionEventType():213,220	НЕТ ⚠️
ORDER_FILLED	TradeService:45	НЕТ ⚠️
TRADE_CREATED	TradeService:94	PositionProjectionHandler:22 + EquityProjectionHandler:22
Ответы
1. Есть ли события без consumers?

Да — 6 событий: ORDER_REJECTED, ORDER_TIMEOUT, ORDER_FAILED, ORDER_CANCELED, ORDER_COMPLETED, ORDER_FILLED. Они попадают в OutboxEventRouter.route() → log.warn("[ROUTER] Не найден обработчик...").

Последствия не критические: доменная мутация уже выполнена в commitExecution() до публикации события. Ордер сохранён, блокировка снята. События служат только для downstream потребителей, которых нет.

2. Есть ли consumers без publishers?

Нет. Все 4 consumer'а имеют активных publisher'ов.

3. Есть ли новые ORDER_REJECTED / ORDER_TIMEOUT / ORDER_FAILED без обработки?

Да — все три. Мутация ордера выполняется в commitExecution(), но downstream-обработчик отсутствует. Событие помечается PROCESSED и роутер пишет warn.

FINAL RESULT
#	Invariant	PASS/FAIL	Причина
1	ORDER_EXECUTED только с non-null qty/price	✅ PASS	resolveCompletionEventType() строка 207-208: двойная проверка перед публикацией
2	REJECTED/TIMEOUT/FAILED_IO/CANCELED не попадают в TradeCreatedEvent	✅ PASS	Разделение event-типов + supports("ORDER_EXECUTED") изолирует handler
3	TradeCreatedEvent без null полей	⚠️ PASS	Path A (ORDER_EXECUTED) защищён тройным guard. Path B (TradeService) полагается на PositionEntity.applyTrade для ошибки
4	PositionEntity.applyTrade без null	✅ PASS	InvalidTradeDataException для null qty/price — ошибка видна, а не скрыта
5	OrderExecutedEvent = executedQuantity, не originalQuantity	✅ PASS	order.getExecutedQuantity() используется в строке 42
6	Нет необработанных fallthrough по статусам	✅ PASS	EXECUTING/CANCELED недостижимы с ORDER_EXECUTED
7	События имеют consumers	⚠️ PARTIAL	6 событий без consumer'ов, но доменная мутация уже выполнена в commitExecution()
Список оставшихся проблем
🔴 CRITICAL — нет
Исходный NPE полностью устранён. Ни один путь не ведёт к BigDecimal.multiply(null).

🟠 HIGH — нет
Все execution-статусы безопасно разведены. Неисполненные ордера не создают TradeCreatedEvent.

🟡 MEDIUM — 1 проблема
#	Проблема	Файл
M1	TradeService.onOrderFilled() (строка 65-66) не проверяет event.getQuantity() и event.getPrice() на null перед сохранением в TradeEntity. Если внешний WebSocket пришлёт событие с null-полями, TradeCreatedEvent будет создан с null, но PositionEntity.applyTrade() выбросит InvalidTradeDataException. Ошибка видна, но нет ранней валидации.	TradeService.java:35-113
🟢 LOW — 2 проблемы
#	Проблема	Файл
L1	ORDER_REJECTED, ORDER_TIMEOUT, ORDER_FAILED, ORDER_CANCELED публикуются без consumer'ов — OutboxEventRouter пишет warn. Доменная мутация уже выполнена, но события «повисают» в логах. Рекомендация: добавить no-op handler, который логирует и не делает ничего, либо не публиковать эти события вообще.	OrderExecutionHandler.java:199-222
L2	FAILED_IO и CANCELED в commitExecution() не мутируют ордер (строки 183-188) — только логирование. Ордер остаётся в EXECUTING, но событие публикуется. Рекомендация: либо добавить вызов order.markAsError() / order.markCancelled(), либо не публиковать событие.