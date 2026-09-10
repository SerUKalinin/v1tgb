1. Полный enum ExecutionResult.Status
   // ExecutionResult.java:19-25
   public enum Status {
   SUCCESS,
   REJECTED,
   CANCELED,
   FAILED_IO,
   TIMEOUT
   }
   Таблица: Producer / Consumer / Meaning
   Status	Producer	Consumers	Meaning (текущий)	Terminal?	Requires reconciliation?
   SUCCESS	BinanceExecutionAdapter.mapToExecutionResult():160 — FILLED/NEW/PARTIALLY_FILLED
   BinanceExecutionAdapter.mapStatusResponse():183 — только FILLED
   BacktestExecutionEngine.verifyOrder():73
   BinanceExecutionEngine.verifyOrderInternal():67
   BinanceExecutionEngine.mapToResult():84	OrderExecutionHandler.commitExecution():171 → order.fill()
   OrderExecutionHandler.resolveCompletionEventType():206
   ReconciliationService.syncOrderWithExchange():202 → order.forceFill()
   BinanceExecutionEngine.verifyOrderInternal():65	Перегружен: и «ордер принят» (NEW), и «частично исполнен» (PARTIALLY_FILLED), и «полностью исполнен» (FILLED). Семантически размыт.	✅ Terminal	❌ Нет
   REJECTED	BinanceExecutionAdapter.mapToExecutionResult():163 — CANCELED/REJECTED/EXPIRED/PENDING_CANCEL
   BinanceExecutionAdapter.mapStatusResponse():183 — NEW/PARTIALLY_FILLED/CANCELED/REJECTED/EXPIRED
   BinanceExecutionAdapter.mapErrorToResult():170 — ошибка 400/-1013/-1111
   ExecutionResult.rejected():72	OrderExecutionHandler.commitExecution():178 → order.markAsRejected()
   ReconciliationService.syncOrderWithExchange():207 → order.markAsRejected() + riskEngine.release()	Перегружен: и «биржа отклонила», и «отменён», и «истёк», и «живой ордер (NEW) при recovery»	✅ Terminal	❌ Нет
   TIMEOUT	BinanceExecutionAdapter.mapErrorToResult():173 — Timeout/504
   ExecutionResult.timeout():88
   BinanceExecutionAdapter.getOrderStatus():96 — любое исключение	BinanceExecutionEngine.execute():50 → recovery flow
   OrderExecutionHandler.commitExecution():180 → order.markAsUnknown()	Запрос не получил ответа от биржи за отведённое время	❌ Не terminal	✅ Да
   FAILED_IO	BinanceExecutionAdapter.mapErrorToResult():175 — все остальные ошибки
   ExecutionResult.failedIo():80	OrderExecutionHandler.commitExecution():183 → только лог (статус ордера не меняется)
   BinanceExecutionEngine.execute():55 — пробрасывается как есть	Сетевая ошибка / ошибка ввода-вывода. Неизвестно, был ли ордер размещён.	❌ Не terminal	✅ Да
   CANCELED	ExecutionResult.canceled():96	OrderExecutionHandler.commitExecution():186 → только лог (статус ордера не меняется)
   ReconciliationService.syncOrderWithExchange():213 → order.markCancelled() + riskEngine.release()	Ордер отменён пользователем или биржей	❌ В commitExecution — статус не меняется	✅ Да
2. Все switch/if конструкции по ExecutionResult.Status
#	Файл	Строка	Status	Action
1	BinanceExecutionAdapter.java	62	REJECTED	return errorResult (предотвращает проброс исключения)
2	BinanceExecutionEngine.java	50	!SUCCESS + TIMEOUT msg	Запуск recovery: verifyOrderInternal()
3	OrderExecutionHandler.java	171	SUCCESS	order.fill(exchangeOrderId, executedQty, executedPrice) → FILLED
4	OrderExecutionHandler.java	178	REJECTED	order.markAsRejected(reason) → REJECTED
5	OrderExecutionHandler.java	180	TIMEOUT	order.markAsUnknown() → UNKNOWN
6	OrderExecutionHandler.java	183	FAILED_IO	log.error(...) — ордер не меняется
7	OrderExecutionHandler.java	186	CANCELED	log.info(...) — ордер не меняется
8	OrderExecutionHandler.java	206	SUCCESS	Проверка hasRealExecution → выбор event type
9	OrderExecutionHandler.java	215	REJECTED/TIMEOUT/FAILED_IO/CANCELED	Switch для resolveCompletionEventType
10	ReconciliationService.java	202	SUCCESS	order.forceFill() → FILLED
11	ReconciliationService.java	207	REJECTED	order.markAsRejected() + riskEngine.release() → REJECTED
12	ReconciliationService.java	213	CANCELED	order.markCancelled() + riskEngine.release() → CANCELED
3. Семантический анализ: что реально означают статусы
   Вопрос: какой статус означает «ордер полностью исполнен»?
   ExecutionResult.Status	Действительно ли?	Почему
   SUCCESS	❌ Нет — перегружен	Включает NEW (0% fill) и PARTIALLY_FILLED
   Ответ: НИ ОДИН статус не означает однозначно «полностью исполнен». SUCCESS означает три разных состояния.

Вопрос: какой статус означает «ордер частично исполнен»?
ExecutionResult.Status	Действительно ли?	Почему
SUCCESS	❌ Нет — перегружен	PARTIALLY_FILLED мапится в SUCCESS, неотличим от FILLED
Ответ: НЕТ отдельного статуса для частичного исполнения.

Вопрос: какой статус означает «ордер принят биржей»?
ExecutionResult.Status	Действительно ли?	Почему
SUCCESS	❌ Нет — перегружен	NEW мапится в SUCCESS с executedQty = 0, но система трактует как FILLED
Ответ: НЕТ статуса «принят, но не исполнен». Этой семантики не существует в модели.

Вопрос: какой статус означает «ордер ещё живёт на бирже»?
ExecutionResult.Status	Действительно ли?	Почему
—	❌	Такого статуса нет. NEW/PARTIALLY_FILLED при recovery → REJECTED
Ответ: НЕТ статуса «ордер открыт на бирже». При recovery живой ордер получает REJECTED.

Вопрос: какой статус означает «ордер окончательно отклонён»?
ExecutionResult.Status	Действительно ли?	Почему
REJECTED	❌ Нет — перегружен	Включает CANCELED, EXPIRED, PENDING_CANCEL, REJECTED, а при recovery — ещё и NEW/PARTIALLY_FILLED
CANCELED	✅ Да — но	Не используется в commitExecution (только лог), используется в ReconciliationService
Ответ: REJECTED перегружен и означает слишком много разных вещей. CANCELED существует номинально, но не меняет статус ордера в основном пути.

Вопрос: какой статус означает «состояние неизвестно»?
ExecutionResult.Status	Действительно ли?	Почему
TIMEOUT	✅ Частично	Означает «ответ не получен», но не «ордер существует, но состояние неизвестно»
FAILED_IO	✅ Частично	Сетевая ошибка — неизвестно, был ли ордер размещён
Ответ: Два статуса покрывают неизвестность, но с разной семантикой (timeout vs io error). Нет единого статуса «неизвестно».

4. Текущая state model: Binance → ExecutionResult → OrderStatus
   Путь placeOrder (mapToExecutionResult, строка 153)
   boolean success = "FILLED".equals(status) || "NEW".equals(status) || "PARTIALLY_FILLED".equals(status);
   Binance Status	ExecutionResult.Status	commitExecution действие	OrderStatus
   NEW	SUCCESS	order.fill()	FILLED 🔴
   PARTIALLY_FILLED	SUCCESS	order.fill()	FILLED 🔴
   FILLED	SUCCESS	order.fill()	FILLED ✅
   CANCELED	REJECTED	order.markAsRejected()	REJECTED ⚠️
   REJECTED	REJECTED	order.markAsRejected()	REJECTED ✅
   EXPIRED	REJECTED	order.markAsRejected()	REJECTED ⚠️
   PENDING_CANCEL	REJECTED	order.markAsRejected()	REJECTED ⚠️
   Путь getOrderStatus / recovery (mapStatusResponse, строка 179)
   boolean success = "FILLED".equals(response.getStatus());
   Binance Status	ExecutionResult.Status	Действие	OrderStatus
   FILLED	SUCCESS	order.fill() / order.forceFill()	FILLED ✅
   NEW	REJECTED	order.markAsRejected()	REJECTED 🔴
   PARTIALLY_FILLED	REJECTED	order.markAsRejected()	REJECTED 🔴
   CANCELED	REJECTED	order.markAsRejected()	REJECTED ⚠️
   REJECTED	REJECTED	order.markAsRejected()	REJECTED ✅
   EXPIRED	REJECTED	order.markAsRejected()	REJECTED ⚠️
   PENDING_CANCEL	REJECTED	order.markAsRejected()	REJECTED ⚠️
   Путь ошибок (mapErrorToResult, строки 167–176)
   Exception pattern	ExecutionResult.Status	Действие	OrderStatus
   400 / -1013 / -1111	REJECTED	order.markAsRejected()	REJECTED
   Timeout / 504	TIMEOUT	order.markAsUnknown()	UNKNOWN
   Всё остальное	FAILED_IO	только лог	не меняется 🔴
5. Целевая модель
   5A. Каких статусов не хватает — и почему
   Предлагаемый статус	Семантика	Какой Binance статус мапится	Почему нужен
   ACCEPTED	Ордер принят биржей, НЕ исполнен (0 fill)	NEW	Текущий код мапит NEW → SUCCESS → FILLED — критическая ошибка. Ордер только размещён.
   PARTIALLY_FILLED	Ордер частично исполнен	PARTIALLY_FILLED	Текущий код мапит в SUCCESS → FILLED. Теряется информация о неисполненном остатке. Должно вызывать order.applyPartialFill().
   OPEN	Ордер живёт на бирже в не-терминальном состоянии	NEW + PARTIALLY_FILLED при recovery	При recovery/getOrderStatus текущий код мапит оба в REJECTED. Ордер жив, но система освобождает капитал — риск двойного расходования.
   UNKNOWN_EXCHANGE_STATE	Состояние неизвестно после ошибки	Нет соответствия (сейчас: TIMEOUT + FAILED_IO частично)	Сейчас два разных статуса с пересекающейся семантикой. Единый статус упрощает reconciliation.
   5B. Целевой enum
   public enum Status {
   // === Терминальные: исполнение завершено ===
   FILLED,              // Полностью исполнен (Binance: FILLED)
   CANCELED,            // Отменён (Binance: CANCELED, PENDING_CANCEL)
   REJECTED,            // Отклонён биржей (Binance: REJECTED, EXPIRED)

   // === Нетерминальные: исполнение в процессе ===
   ACCEPTED,            // Принят биржей, 0% fill (Binance: NEW)
   PARTIALLY_FILLED,    // Частично исполнен (Binance: PARTIALLY_FILLED)

   // === Нетерминальные: состояние неизвестно ===
   OPEN_STATUS_UNKNOWN, // Ордер существует на бирже, статус неясен (recovery: NEW/PARTIALLY_FILLED/не-FILLED)
   EXCHANGE_STATE_UNKNOWN, // Неизвестно, был ли ордер размещён (network error, timeout)

   // === Нетерминальные: технические ===
   IO_ERROR             // Ошибка ввода-вывода (не было попытки размещения)
   }
   5C. Целевой маппинг: Binance → ExecutionResult.Status
   Binance Status	ExecutionResult.Status	Семантика
   NEW	ACCEPTED	Принят, ждёт исполнения
   PARTIALLY_FILLED	PARTIALLY_FILLED	Частично исполнен, остаток в рынке
   FILLED	FILLED	Полностью исполнен
   CANCELED	CANCELED	Отменён
   REJECTED	REJECTED	Отклонён биржей
   EXPIRED	REJECTED	Истёк = окончательно отклонён
   PENDING_CANCEL	CANCELED	В процессе отмены → считаем отменённым
   5D. Целевой маппинг: ExecutionResult.Status → OrderStatus
   ExecutionResult.Status	Действие	OrderStatus	Терминальный?
   FILLED	order.fill()	FILLED	✅ Да
   CANCELED	order.markCancelled() + riskEngine.release()	CANCELED	✅ Да
   REJECTED	order.markAsRejected() + riskEngine.release()	REJECTED	✅ Да
   ACCEPTED	order.markSent() или новый метод	SENT_TO_EXCHANGE	❌ Нет — ждать fill
   PARTIALLY_FILLED	order.applyPartialFill()	PARTIALLY_FILLED	❌ Нет — ждать fill/cancel
   OPEN_STATUS_UNKNOWN	order.markAsUnknown()	UNKNOWN	❌ Нет — требует reconciliation
   EXCHANGE_STATE_UNKNOWN	order.markAsUnknown()	UNKNOWN	❌ Нет — требует reconciliation
   IO_ERROR	Не менять статус + retry	Текущий статус	❌ Нет — требует retry
   5E. Сводная таблица изменений
   Текущее	Проблема	Целевое
   SUCCESS (4 семантики)	Перегрузка	FILLED / ACCEPTED / PARTIALLY_FILLED — 3 отдельных статуса
   REJECTED (5+ семантик)	Перегрузка	REJECTED / CANCELED / OPEN_STATUS_UNKNOWN — 3 отдельных статуса
   TIMEOUT + FAILED_IO	Два статуса с пересечением	EXCHANGE_STATE_UNKNOWN — единый статус неопределённости
   FAILED_IO не меняет статус ордера	Ордер зависает в EXECUTING	IO_ERROR — явный retry-статус
   CANCELED не меняет статус в commitExecution	Ордер зависает	CANCELED → order.markCancelled()
   Нет ACCEPTED	NEW → FILLED (критическая ошибка)	ACCEPTED → SENT_TO_EXCHANGE
   Нет PARTIALLY_FILLED	PARTIALLY_FILLED → FILLED (потеря остатка)	PARTIALLY_FILLED → order.applyPartialFill()
6. Финальный вывод
   Корневые проблемы текущей модели:
   SUCCESS — семантическая ловушка: объединяет 4 разных состояния биржи (NEW, PARTIALLY_FILLED, FILLED, + fake backtest). Потребители (OrderExecutionHandler, ReconciliationService) не могут различить их и применяют order.fill() ко всем.

REJECTED — мусорная корзина: всё, что не SUCCESS в mapToExecutionResult, падает в REJECTED. При recovery mapStatusResponse мапит живые ордера (NEW, PARTIALLY_FILLED) тоже в REJECTED. ReconciliationService на REJECTED освобождает капитал — двойное расходование.

Отсутствие промежуточных статусов: нет ACCEPTED и PARTIALLY_FILLED, поэтому система не может отличить «ордер размещён, ждём» от «ордер исполнен, финализируем».

CANCELED и FAILED_IO не завершают ордер в commitExecution: ордер остаётся в текущем статусе без явного перехода — технический долг.

Два расходящихся маппинга: mapToExecutionResult и mapStatusResponse дают противоположные результаты для NEW и PARTIALLY_FILLED — инвариант нарушения при любом recovery.

Не хватает статусов (необходимый минимум):
Статус	Критичность
ACCEPTED	🔴 Critical
PARTIALLY_FILLED (как ExecutionResult)	🔴 Critical
OPEN_STATUS_UNKNOWN	🟡 High
EXCHANGE_STATE_UNKNOWN (вместо TIMEOUT+FAILED_IO)	🟡 High