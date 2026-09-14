DIAGNOSTICS: ORDER_EXECUTED → NPE
Root Cause
Order.markAsRejected() и Order.markAsUnknown() никогда не выставляют поле averagePrice (оно остаётся null), но OrderExecutedEvent.from(order) безоговорочно копирует его в price события, а OrderExecutedEventHandler строит TradeCreatedEvent с price=null и передаёт в PositionEntity.applyTrade() → NPE на tradeQty.multiply(null).

Полная цепочка от commitExecution до NPE
#	Файл	Строки	Что происходит
1	OrderExecutionHandler.java	178–183	REJECTED → order.markAsRejected(context, ...) — не трогает averagePrice; TIMEOUT → order.markAsUnknown(context) — тоже не трогает.
2	Order.java	264–272	markAsRejected(): устанавливает только status=REJECTED и rejectionReason. averagePrice и executedQuantity остаются null.
3	Order.java	284–291	markAsUnknown(): устанавливает только status=UNKNOWN. Те же поля остаются null.
4	OrderExecutionHandler.java	188–193	outboxService.publishEvent(..., OrderExecutedEvent.from(order)) — вызывается для всех статусов, включая REJECTED/TIMEOUT.
5	OrderExecutedEvent.java	43	order.getAveragePrice() → null — сохраняется в поле price DTO.
6	Outbox → OrderExecutedEventHandler.java	45, 64	Десериализует payload; executedQty = payload.getQuantity() — но это order.getQuantity() (исходное количество ордера, строка 42), а не order.getExecutedQuantity()!
7	OrderExecutedEventHandler.java	78	Guard: executedQty != null && executedQty.compareTo(BigDecimal.ZERO) > 0. Исходное quantity > 0 для любого BUY/SELL ордера → guard пропускает REJECTED/TIMEOUT.
8	OrderExecutedEventHandler.java	81–93	Строит TradeCreatedEvent с executionPrice = null, передаёт в positionService.updatePosition(tradeEvent).
9	PositionService.java	82	entity.applyTrade(event.getQuantity(), event.getPrice() /* null */, event.getTradeId())
10	PositionEntity.java	80	tradeQty.multiply(tradePrice) → 💥 NPE: tradePrice is null.
Почему guard на executedQty не защищает от null price
Критическая ошибка — в OrderExecutedEvent.from(order), строка 42:

order.getQuantity(),       // ← ИСХОДНОЕ количество ордера, а НЕ executedQuantity
order.getAveragePrice(),   // ← null для REJECTED/TIMEOUT
order.getQuantity() — это originalQuantity из конструктора (строка 54 файла Order.java), никогда не бывает 0. Поле executedQuantity (строка 43 Order.java) — то, которое реально исполнено — в from() не используется вообще.

В handler'е (строка 78):

if (executedQty != null && executedQty.compareTo(BigDecimal.ZERO) > 0)
Проверяет payload.getQuantity() (исходное количество), а не payload.getPrice(). Исходное количество всегда > 0 → guard всегда true для любого ордера — включая REJECTED с price=null.

Почему retry не помогает
При первом проходе:

consume() → positionService.updatePosition(tradeEvent) → NPE на строке 94
idempotencyService.markAsProcessed(...) на строке 98 — никогда не выполняется
При retry:

Idempotency guard (строка 51) не срабатывает — ключ не был сохранён
Order уже в REJECTED/UNKNOWN, но handler всё равно заходит в ветку executedQty > 0
Снова строит TradeCreatedEvent с price=null → тот же NPE
Бесконечный цикл retry → NPE.

Unsafe assumptions в пайплайне
#	Где	Предположение	Почему неверно
1	OrderExecutedEvent.from() L42	order.getQuantity() ≡ executed quantity	Это originalQuantity, всегда > 0. Нужно order.getExecutedQuantity().
2	OrderExecutedEvent.from() L43	order.getAveragePrice() всегда не-null	Только fill()/applyPartialFill()/forceFill() выставляют поле. markAsRejected()/markAsUnknown() — нет.
3	OrderExecutionHandler.commitExecution() L188–193	Событие ORDER_EXECUTED нужно публиковать для всех статусов	REJECTED/TIMEOUT не являются «исполнением» в смысле сделки. Публикация TradeCreatedEvent для них бессмысленна.
4	OrderExecutedEventHandler.consume() L78	executedQty > 0 ⇒ была реальная сделка (price ≠ null)	REJECTED ордер имеет originalQuantity > 0, но price = null.
5	OrderExecutedEventHandler.consume() L70–76	Все статусы обработаны	UNKNOWN (TIMEOUT) вообще не имеет ветки в switch — падает в executedQty > 0 без обновления статуса.
6	PositionEntity.applyTrade() L67–68	tradePrice проверен вызывающей стороной	Проверяется только tradeQty, но не tradePrice.
Рекомендации (где исправлять)
Приоритет 1 — предотвратить публикацию TradeCreatedEvent для неисполненных ордеров:

В OrderExecutionHandler.commitExecution() (строки 171–193) — публиковать ORDER_EXECUTED только для SUCCESS (и возможно PARTIALLY_FILLED). Для REJECTED/TIMEOUT публиковать отдельный тип события (например ORDER_REJECTED / ORDER_TIMEOUT) без создания TradeCreatedEvent.

Или, как минимум, в OrderExecutedEventHandler.consume() добавить guard перед построением TradeCreatedEvent:

// Строка 78 — добавить проверку price:
if (executedQty != null && executedQty.compareTo(BigDecimal.ZERO) > 0
&& executionPrice != null) {   // ← добавить
Приоритет 2 — исправить OrderExecutedEvent.from():

Строка 42: заменить order.getQuantity() на order.getExecutedQuantity().

Приоритет 3 — добавить обработку UNKNOWN в handler:

Строки 70–76: добавить else if (targetStatus == OrderStatus.UNKNOWN) с логикой обработки таймаута.

Приоритет 4 — защитный null-check в PositionEntity.applyTrade():

Строка 67: if (tradeQty == null || tradePrice == null || tradeQty.signum() == 0) return;