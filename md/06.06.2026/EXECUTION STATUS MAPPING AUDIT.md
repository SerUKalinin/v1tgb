EXECUTION STATUS MAPPING AUDIT
1. Все места использования ExecutionResult.Status
   SUCCESS — 5 мест в коде
#	Файл	Строка	Контекст
1	BinanceExecutionAdapter.java	160	mapToExecutionResult() — билдер
2	BinanceExecutionAdapter.java	183	mapStatusResponse() — билдер
3	BacktestExecutionEngine.java	73	verifyOrder() — билдер
4	OrderExecutionHandler.java	171	commitExecution() — проверка
5	ReconciliationService.java	202	syncOrderWithExchange() — проверка
REJECTED — 5 мест
#	Файл	Строка	Контекст
1	BinanceExecutionAdapter.java	62	doPlaceOrder() — проверка errorResult
2	BinanceExecutionAdapter.java	163	mapToExecutionResult() — вызов ExecutionResult.rejected()
3	BinanceExecutionAdapter.java	170	mapErrorToResult() — ошибка 400/-1013/-1111
4	BinanceExecutionAdapter.java	183	mapStatusResponse() — !FILLED → REJECTED
5	OrderExecutionHandler.java	178	commitExecution() — проверка
6	ReconciliationService.java	207	syncOrderWithExchange() — проверка
TIMEOUT — 2 места
#	Файл	Строка	Контекст
1	BinanceExecutionAdapter.java	173	mapErrorToResult() — ошибка Timeout/504
2	OrderExecutionHandler.java	180	commitExecution() — проверка
CANCELED — 1 место
#	Файл	Строка	Контекст
1	ReconciliationService.java	213	syncOrderWithExchange() — проверка
(+1)	OrderExecutionHandler.java	186	commitExecution() — проверка (логирование, не меняет статус ордера)
FAILED_IO — 1 место
#	Файл	Строка	Контекст
1	BinanceExecutionAdapter.java	175	mapErrorToResult() — все остальные ошибки
2. Полный маппинг: Binance Status → ExecutionResult.Status
   В системе два независимых маппинга — критическое различие.

Маппинг #1: BinanceExecutionAdapter.mapToExecutionResult() (строки 151–165)
Используется в пути placeOrder() → doPlaceOrder() → ответ POST /api/v3/order.

// строка 153 — ЕДИНСТВЕННАЯ строка маппинга
boolean success = "FILLED".equals(status) || "NEW".equals(status) || "PARTIALLY_FILLED".equals(status);
Binance Status	ExecutionResult.Status
NEW	✅ SUCCESS
PARTIALLY_FILLED	✅ SUCCESS
FILLED	✅ SUCCESS
CANCELED	❌ REJECTED
REJECTED	❌ REJECTED
EXPIRED	❌ REJECTED
PENDING_CANCEL	❌ REJECTED
Маппинг #2: BinanceExecutionAdapter.mapStatusResponse() (строки 178–186)
Используется в путях getOrderStatus() (recovery), verifyOrder() (watchdog), ReconciliationService.syncOrderWithExchange().

// строка 179 — ЕДИНСТВЕННАЯ строка маппинга
boolean success = "FILLED".equals(response.getStatus());
Binance Status	ExecutionResult.Status
FILLED	✅ SUCCESS
NEW	❌ REJECTED
PARTIALLY_FILLED	❌ REJECTED
CANCELED	❌ REJECTED
REJECTED	❌ REJECTED
EXPIRED	❌ REJECTED
PENDING_CANCEL	❌ REJECTED
3. Может ли Binance NEW → ExecutionResult.Status.SUCCESS?
   🔴 ДА. Строка 153:
   boolean success = "FILLED".equals(status) || "NEW".equals(status) || "PARTIALLY_FILLED".equals(status);
   NEW явно включён в условие success.

Это происходит в пути placeOrder(): когда Binance принимает ордер и возвращает статус NEW, адаптер создаёт:

// строки 156-161
ExecutionResult.builder()
.orderId(order.getId())
.exchangeOrderId(response.get("orderId").toString())
.executedQty(new BigDecimal((String) response.get("executedQty"))) // = "0"
.status(ExecutionResult.Status.SUCCESS)  // ← SUCCESS при NEW!
.build();
Итог: только что размещённый, неисполненный ордер (Binance NEW) получает ExecutionResult.Status.SUCCESS с executedQty = "0".

4. Может ли Binance PARTIALLY_FILLED → ExecutionResult.Status.SUCCESS?
   🔴 ДА. Строка 153:
   boolean success = "FILLED".equals(status) || "NEW".equals(status) || "PARTIALLY_FILLED".equals(status);
   PARTIALLY_FILLED явно включён в условие success.

5. Полный путь: Binance Status → ExecutionResult → OrderStatus
   Путь A: OrderExecutionHandler.consume() → placeOrder → commitExecution()
   (строки 90, 171–189 OrderExecutionHandler.java)

POST /api/v3/order → mapToExecutionResult() → commitExecution()
Binance Status	ExecutionResult	Код в commitExecution	Итоговый OrderStatus
NEW	SUCCESS	order.fill() → строка 207	FILLED 🔴
PARTIALLY_FILLED	SUCCESS	order.fill() → строка 207	FILLED 🔴
FILLED	SUCCESS	order.fill() → строка 207	FILLED ✅
CANCELED	REJECTED	order.markAsRejected() → строка 270	REJECTED ⚠️
REJECTED	REJECTED	order.markAsRejected() → строка 270	REJECTED ✅
EXPIRED	REJECTED	order.markAsRejected() → строка 270	REJECTED ⚠️
PENDING_CANCEL	REJECTED	order.markAsRejected() → строка 270	REJECTED ⚠️
ошибка I/O	FAILED_IO	только лог (строка 184)	БЕЗ ИЗМЕНЕНИЙ 🔴
ошибка исключения	TIMEOUT	order.markAsUnknown() → строка 290	UNKNOWN
Путь B: BinanceExecutionEngine.verifyOrderInternal() → getOrderStatus() → mapStatusResponse()
(строки 62–81 BinanceExecutionEngine.java)

Используется при recovery после таймаута.

GET /api/v3/order → mapStatusResponse() → verifyOrderInternal()
Binance Status	mapStatusResponse → ExecutionResult	verifyOrderInternal	Итоговый ExecutionResult
FILLED	SUCCESS	Оборачивается в ExecutionResult.success() с order.getPrice()	SUCCESS (executedPrice = цена ордера)
NEW	REJECTED	Не оборачивается → падает в REJECTED	REJECTED 🔴
PARTIALLY_FILLED	REJECTED	Не оборачивается → падает в REJECTED	REJECTED 🔴
Этот путь затем возвращается в BinanceExecutionEngine.execute() → OrderExecutionHandler.commitExecution(), где REJECTED → order.markAsRejected().

Путь C: ReconciliationService.syncOrderWithExchange() → getOrderStatus() → mapStatusResponse()
(строки 199–204 ReconciliationService.java)

Binance Status	ExecutionResult	Код в syncOrderWithExchange	Итоговый OrderStatus
FILLED	SUCCESS	order.forceFill()	FILLED ✅
NEW	REJECTED	order.markAsRejected()	REJECTED 🔴
PARTIALLY_FILLED	REJECTED	order.markAsRejected()	REJECTED 🔴
CANCELED	REJECTED	order.markAsRejected()	REJECTED ⚠️
6. Финальная таблица
   Binance Status	ExecutionResult (placeOrder)	ExecutionResult (getOrderStatus)	OrderStatus (placeOrder)	OrderStatus (recovery)	OrderStatus (recon)
   NEW	SUCCESS 🔴	REJECTED 🔴	FILLED 🔴	REJECTED 🔴	REJECTED 🔴
   PARTIALLY_FILLED	SUCCESS 🔴	REJECTED 🔴	FILLED 🔴	REJECTED 🔴	REJECTED 🔴
   FILLED	SUCCESS ✅	SUCCESS ✅	FILLED ✅	FILLED ✅	FILLED ✅
   CANCELED	REJECTED ⚠️	REJECTED ⚠️	REJECTED ⚠️	REJECTED ⚠️	REJECTED ⚠️
   REJECTED	REJECTED ✅	REJECTED ✅	REJECTED ✅	REJECTED ✅	REJECTED ✅
   EXPIRED	REJECTED ⚠️	REJECTED ⚠️	REJECTED ⚠️	REJECTED ⚠️	REJECTED ⚠️
7. Случаи несоответствия: биржевой статус ≠ итоговый OrderStatus
   🔴 Critical Mismatch #1: NEW → FILLED
   Причина: mapToExecutionResult (строка 153) считает NEW = SUCCESS
   Путь: placeOrder() → commitExecution() → order.fill() → OrderStatus.FILLED
   Реальность: Ордер только что размещён на бирже и не исполнен. executedQty = 0, executedPrice = null.
   Строка: BinanceExecutionAdapter.java:153 — "NEW".equals(status) в условии success
   🔴 Critical Mismatch #2: PARTIALLY_FILLED → FILLED
   Причина: mapToExecutionResult (строка 153) считает PARTIALLY_FILLED = SUCCESS
   Путь: placeOrder() → commitExecution() → order.fill() → OrderStatus.FILLED
   Реальность: Ордер исполнен частично, в системе помечается как полностью FILLED
   Строка: BinanceExecutionAdapter.java:153 — "PARTIALLY_FILLED".equals(status) в условии success
   🔴 Critical Mismatch #3: NEW/PARTIALLY_FILLED → REJECTED (через recovery)
   Причина: mapStatusResponse (строка 179) считает только FILLED = SUCCESS
   Путь: getOrderStatus() → REJECTED → order.markAsRejected() → OrderStatus.REJECTED
   Реальность: Ордер существует на бирже (живой), но система помечает его как REJECTED и освобождает капитал в RiskEngine
   Строка: BinanceExecutionAdapter.java:179 — только "FILLED" в условии
   🟡 Warning Mismatch #4: CANCELED/EXPIRED → REJECTED
   Причина: mapToExecutionResult (строка 153) — else → ExecutionResult.rejected(order.getId(), status)
   Путь: placeOrder() → REJECTED → order.markAsRejected() → OrderStatus.REJECTED
   Строка: BinanceExecutionAdapter.java:163
   🟡 Warning Mismatch #5: FAILED_IO → статус ордера не меняется
   Причина: commitExecution() (строка 183–185) — только log.error, нет вызова метода ордера
   Путь: placeOrder() → FAILED_IO → ордер остаётся в текущем статусе (EXECUTING)
   Строка: OrderExecutionHandler.java:183-185
   🟡 Warning Mismatch #6: CANCELED → статус ордера не меняется
   Причина: commitExecution() (строка 186–188) — только log.info, нет вызова order.markCancelled()
   Путь: ордер остаётся в текущем статусе
   Строка: OrderExecutionHandler.java:186-188
8. Финальный вывод
   🔴 Root Cause
   Два маппинга статусов радикально расходятся:

mapToExecutionResult (placeOrder)	mapStatusResponse (getOrderStatus)
Условие SUCCESS	FILLED || NEW || PARTIALLY_FILLED	только FILLED
Строка	153	179
NEW →	SUCCESS	REJECTED
PARTIALLY_FILLED →	SUCCESS	REJECTED
Ключевые проблемы:
NEW → FILLED: только что размещённый неисполненный ордер получает терминальный статус FILLED — это критическая семантическая ошибка.

PARTIALLY_FILLED → FILLED: частичное исполнение трактуется как полное — потеря данных о неисполненном остатке.

NEW/PARTIALLY_FILLED → REJECTED при recovery: живой ордер на бирже помечается как отклонённый, капитал освобождается → риск двойного расходования.

Расхождение маппингов: mapToExecutionResult и mapStatusResponse дают противоположные результаты для одних и тех же статусов Binance.

Рекомендация
Унифицировать маппинг: только FILLED → SUCCESS. Убрать "NEW" и "PARTIALLY_FILLED" из условия в строке 153 BinanceExecutionAdapter.java. NEW должен вести к промежуточному статусу (не SUCCESS) — например, новый ExecutionResult.Status.ACCEPTED. PARTIALLY_FILLED — аналогично, с последующей реконсиляцией.