1. Все вызовы order.applyPartialFill(...)
   📍 Единственный production-вызов
#	Файл	Строка	Контекст
1	OrderExecutedEventHandler.java	73	order.applyPartialFill(context, executedQty, executionPrice) — вызывается когда targetStatus == OrderStatus.PARTIALLY_FILLED
💀 Мёртвый код/тесты
#	Файл	Строка	Статус
2	OrderTest.java	35	Закомментирован (//order.applyPartialFill(...))
Вывод: applyPartialFill существует, его метод корректен (с idempotency guards), OrderExecutedEventHandler готов его вызывать. НО ни один producer никогда не создаёт событие с OrderStatus.PARTIALLY_FILLED, потому что commitExecution() всегда вызывает order.fill() → OrderStatus.FILLED.

2. Все места, где читаются executedQuantity / averagePrice
   Через order.getExecutedQuantity()
#	Файл	Строка	Использование
1	OrderExecutedEvent.from()	42	order.getExecutedQuantity() → поле quantity события
2	Order.getRemainingQuantity()	295	originalQuantity - executedQuantity → расчёт остатка
3	OrderExecutionHandler.resolveCompletionEventType()	207	order.getExecutedQuantity() != null — проверка на реальное исполнение
Через order.getAveragePrice()
#	Файл	Строка	Использование
1	OrderExecutedEvent.from()	43	order.getAveragePrice() → поле price события
2	OrderExecutionHandler.resolveCompletionEventType()	208	order.getAveragePrice() != null — проверка на реальное исполнение
Через order.getRemainingQuantity() (производное)
#	Файл	Строка	Использование
1	OrderCompensationService.releasePartial()	22	remainingQty = order.getRemainingQuantity() → освобождение капитала
Вывод: Оба поля читаются корректно. getRemainingQuantity() правильно вычисляет остаток даже при частичном fill. OrderExecutedEvent.from() использует именно getExecutedQuantity() (а не originalQuantity), что корректно для частичных исполнений.

3. Места, где проверяется OrderStatus.FILLED без учёта PARTIALLY_FILLED
   🔴 BUG #1: ExecutionOwnershipValidator.validateExecutionOwnership()
   Файл: ExecutionOwnershipValidator.java, строка 25

if (order.getStatus() == OrderStatus.FILLED) {
return;   // ← только FILLED разрешён для retry
}
if (OrderStateTransitionPolicy.isTerminal(order.getStatus())) {
throw new ExecutionOwnershipException(...);
}
Анализ: Если ордер в PARTIALLY_FILLED:

Не FILLED → не возвращает
isTerminal(PARTIALLY_FILLED) = false (в графе есть переходы)
Проходит валидацию ✅
Итог: PARTIALLY_FILLED проходит эту проверку. FALLTHROUGH не является блокирующим. Но семантически: retry SUCCESS → order.fill() ПОВЕРХ PARTIALLY_FILLED → статус станет FILLED, что скроет частичное исполнение.

🔴 BUG #2: OrderExecutionHandler.commitExecution() — идемпотентность
Файл: OrderExecutionHandler.java, строка 162

if (order.getStatus() == OrderStatus.FILLED) {
return;   // idempotent guard
}
Анализ: Если ордер уже PARTIALLY_FILLED, guard не срабатывает — код идёт дальше и вызывает order.fill() (строка 172), что переводит ордер в FILLED.

Последствия:

Retry после частичного fill → ордер становится FILLED (теряется информация о частичности)
executedQuantity перезаписывается новым значением (возможно, дельтой вместо полного fill)
🟡 WARNING #3: OrderStateTransitionPolicy.mapExecutionResult()
Файл: OrderStateTransitionPolicy.java, строка 118-121

public static OrderStatus mapExecutionResult(ExecutionResult.Status resultStatus) {
return switch (resultStatus) {
case SUCCESS -> OrderStatus.FILLED;   // ← всегда FILLED
...
};
}
Нет пути SUCCESS → PARTIALLY_FILLED. Этот метод не используется в горячем пути (commitExecution его не вызывает), но показывает намерение: SUCCESS ≡ FILLED.

🟡 WARNING #4: OrderRepositoryAdapter.claimForReconciliation()
Файл: OrderRepositoryAdapter.java, строка 80

boolean isExecuting = entity.getStatus() == OrderStatus.EXECUTING;
Проверяет только EXECUTING для блокировки reconciliation. PARTIALLY_FILLED не блокируется — reconciliation сможет захватить такой ордер. Это корректно, т.к. PARTIALLY_FILLED включён в getReconcilableStatuses().

4. Посервисный анализ готовности к PARTIALLY_FILLED
   RiskEngine
   Критерий	Статус
   Supported?	✅ Да
   Broken?	Нет
   Potential bug?	Нет
   Доказательство: RiskEngine.release(context, amount, reason) оперирует суммой, не статусом. OrderCompensationService.releasePartial() (строка 20) использует getRemainingQuantity() для расчёта освобождаемого капитала — корректно для частичного fill.

PositionService
Критерий	Статус
Supported?	✅ Да
Broken?	Нет
Potential bug?	Нет
Доказательство: PositionEntity.applyTrade() (строка 68) работает с tradeQty/tradePrice — чистый дельта-расчёт. PositionReducer.reduce() вычисляет newNetQuantity = currentQty + tradeQty и newAveragePrice через weighted average. Не зависит от статуса ордера. Частичный fill порождает частичную сделку → частичное обновление позиции. Всё корректно.

TradeService
Критерий	Статус
Supported?	✅ Да
Broken?	Нет
Potential bug?	Нет
Доказательство: TradeService.onOrderFilled() (строка 35) создаёт TradeEntity из OrderFilledEvent — не проверяет статус ордера. Поля quantity/price берутся из события. Для частичного fill это будет частичный quantity и средняя цена.

PnL (EquityService + PositionReducer)
Критерий	Статус
Supported?	✅ Да
Broken?	Нет
Potential bug?	Нет
Доказательство:

PositionReducer.calculateNewAvgPrice() (строка 57) — усреднение по всем сделкам, корректно для частичных fills
PositionReducer.calculateTradePnl() (строка 77) — фиксирует PnL только на закрываемую часть объёма, корректно
EquityService (строка 51) — unrealized PnL = (currentPrice - avgEntryPrice) × netQuantity — корректно
Reconciliation
Критерий	Статус
Supported?	⚠️ Частично
Broken?	Есть проблемы
Potential bug?	Да
Доказательство:

getReconcilableStatuses() включает PARTIALLY_FILLED ✅
isTerminal(PARTIALLY_FILLED) = false → reconciliation НЕ блокируется ✅
НО: syncOrderWithExchange() (строка 202) вызывает order.forceFill() для SUCCESS, что перезаписывает executedQuantity/averagePrice полными значениями с биржи. Если биржа всё ещё в PARTIALLY_FILLED, а система уже в PARTIALLY_FILLED, повторная реконсиляция перезапишет корректные данные. Но это idempotent — перезапишет теми же значениями.
НО: mapStatusResponse() (строка 179) мапит Binance PARTIALLY_FILLED → ExecutionResult.Status.REJECTED, что приведёт к order.markAsRejected() и освобождению капитала. Это критический баг, но он в маппинге статусов, а не в reconciliation.
5. Можно ли сегодня безопасно переводить Binance PARTIALLY_FILLED в OrderStatus.PARTIALLY_FILLED?
   Ответ: NO 🔴
   Доказательства:
   Блокирующая проблема #1: commitExecution() всегда вызывает order.fill()
   Файл: OrderExecutionHandler.java, строка 171-177

if (result.getStatus() == ExecutionResult.Status.SUCCESS) {
order.fill(context,
result.getExchangeOrderId(),
result.getExecutedQty(),
result.getExecutedPrice()
);
}
Нет ветки для PARTIALLY_FILLED. order.fill() → OrderStatus.FILLED. Даже если ExecutionResult.Status будет содержать информацию о частичности, commitExecution() проигнорирует её.

Блокирующая проблема #2: Нет PARTIALLY_FILLED в ExecutionResult.Status
ExecutionResult.Status содержит: SUCCESS, REJECTED, CANCELED, FAILED_IO, TIMEOUT. Нет отдельного PARTIALLY_FILLED. Контракт SUCCESS → order.fill() жёстко зашит в commitExecution().

Блокирующая проблема #3: Retry идемпотентность сломана
Файл: OrderExecutionHandler.java, строка 162

if (order.getStatus() == OrderStatus.FILLED) { return; }
Не защищает от retry на PARTIALLY_FILLED. При retry:

Первый вызов: order.fill() → FILLED ✅
... но если бы был PARTIALLY_FILLED:
Первый вызов: applyPartialFill() → PARTIALLY_FILLED
Retry: guard не срабатывает, код идёт в order.fill() → FILLED 🔴
Блокирующая проблема #4: Маппинг в mapStatusResponse() при recovery
Файл: BinanceExecutionAdapter.java, строка 179

boolean success = "FILLED".equals(response.getStatus());
При recovery (после таймаута) Binance PARTIALLY_FILLED → REJECTED → order.markAsRejected() → освобождение капитала. Живой ордер с частичным исполнением будет отклонён.

Финальная таблица
Компонент	PARTIALLY_FILLED	Проблема
Order.applyPartialFill()	✅	Метод готов, idempotent
OrderExecutedEventHandler	✅	Обрабатывает ветку PARTIALLY_FILLED
OrderExecutionHandler.commitExecution()	❌	Всегда вызывает fill(), нет ветки applyPartialFill()
ExecutionResult.Status	❌	Нет статуса PARTIALLY_FILLED
mapToExecutionResult()	❌	PARTIALLY_FILLED → SUCCESS
mapStatusResponse()	❌	PARTIALLY_FILLED → REJECTED (recovery)
ExecutionOwnershipValidator	⚠️	Проходит, но retry fill поверх partial
Idempotency guard (commitExecution)	❌	Только FILLED, не защищает PARTIALLY_FILLED
RiskEngine	✅	Оперирует суммами
PositionService	✅	Дельта-расчёт
TradeService	✅	Не проверяет статус
PnL / EquityService	✅	Weighted average
Reconciliation	⚠️	PARTIALLY_FILLED включён в reconcilable, но forceFill перезаписывает
OrderCompensationService.releasePartial()	✅	Использует getRemainingQuantity()
Что нужно для поддержки PARTIALLY_FILLED (минимальный набор)
Добавить PARTIALLY_FILLED в ExecutionResult.Status
В mapToExecutionResult(): Binance PARTIALLY_FILLED → ExecutionResult.Status.PARTIALLY_FILLED
В mapStatusResponse(): Binance PARTIALLY_FILLED → ExecutionResult.Status.PARTIALLY_FILLED (вместо REJECTED)
В commitExecution(): добавить ветку PARTIALLY_FILLED → order.applyPartialFill()
В idempotency guard (строка 162): добавить || order.getStatus() == OrderStatus.PARTIALLY_FILLED
В OrderStateTransitionPolicy.mapExecutionResult(): добавить PARTIALLY_FILLED → OrderStatus.PARTIALLY_FILLED
В ReconciliationService.syncOrderWithExchange(): PARTIALLY_FILLED → обновлять executedQuantity (не forceFill)