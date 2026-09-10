CRITICAL
Изменения без которых система допускает потерю денег, двойное расходование, или необратимую порчу состояния ордера.

C1 — ExecutionResult.Status: добавить ACCEPTED, PARTIALLY_FILLED, EXCHANGE_STATE_UNKNOWN
Параметр	Значение
Файл	domain/model/ExecutionResult.java
Метод	enum Status (строка 19–25)
Сейчас	SUCCESS, REJECTED, CANCELED, FAILED_IO, TIMEOUT
Стало	FILLED, REJECTED, CANCELED, ACCEPTED, PARTIALLY_FILLED, EXCHANGE_STATE_UNKNOWN
Причина	SUCCESS перегружен (4 семантики). TIMEOUT и FAILED_IO дублируют друг друга. Новые статусы необходимы для корректного маппинга Binance состояний.
Зависит от	— (автономное изменение)
Ломает	всех потребителей ExecutionResult.Status в switch/if — нужно обновить в том же PR
C2 — BinanceExecutionAdapter.mapToExecutionResult(): убрать NEW и PARTIALLY_FILLED из SUCCESS
Параметр	Значение
Файл	infrastructure/binance/BinanceExecutionAdapter.java
Метод	mapToExecutionResult() (строка 151–165)
Сейчас	boolean success = "FILLED" || "NEW" || "PARTIALLY_FILLED" → все в SUCCESS
Стало	Только "FILLED" → FILLED; "NEW" → ACCEPTED; "PARTIALLY_FILLED" → PARTIALLY_FILLED; остальное → REJECTED/CANCELED
Причина	NEW → SUCCESS → FILLED: неисполненный ордер получает терминальный статус. PARTIALLY_FILLED → SUCCESS → FILLED: частичное исполнение скрывается. КРИТИЧЕСКАЯ ОШИБКА СЕМАНТИКИ.
Зависит от	C1
C3 — BinanceExecutionAdapter.mapStatusResponse(): исправить маппинг
Параметр	Значение
Файл	infrastructure/binance/BinanceExecutionAdapter.java
Метод	mapStatusResponse() (строка 178–186)
Сейчас	Только "FILLED" → SUCCESS; всё остальное → REJECTED
Стало	"FILLED" → FILLED; "NEW" → ACCEPTED; "PARTIALLY_FILLED" → PARTIALLY_FILLED; "CANCELED"/"PENDING_CANCEL" → CANCELED; "REJECTED"/"EXPIRED" → REJECTED
Причина	При recovery/getOrderStatus живой ордер (NEW, PARTIALLY_FILLED) попадает в REJECTED → order.markAsRejected() → освобождение капитала. КРИТИЧЕСКИЙ РИСК ДВОЙНОГО РАСХОДОВАНИЯ.
Зависит от	C1
C4 — OrderExecutionHandler.commitExecution(): переписать ветвление статусов
Параметр	Значение
Файл	application/service/execution/OrderExecutionHandler.java
Метод	commitExecution() (строка 171–189)
Сейчас	SUCCESS → order.fill(), REJECTED → markAsRejected(), TIMEOUT → markAsUnknown(), FAILED_IO → log.error, CANCELED → log.info
Стало	FILLED → order.fill(), PARTIALLY_FILLED → order.applyPartialFill(), ACCEPTED → order.markAccepted(), REJECTED → markAsRejected() + riskEngine.release(), CANCELED → markCancelled() + riskEngine.release(), EXCHANGE_STATE_UNKNOWN → markAsUnknown()
Причина	Нет обработки ACCEPTED и PARTIALLY_FILLED. FAILED_IO и CANCELED не меняют статус ордера — ордер зависает в EXECUTING. CANCELED не освобождает капитал.
Зависит от	C1
C5 — ExecutionResult.success() фабричный метод: переименовать, добавить executedPrice
Параметр	Значение
Файл	domain/model/ExecutionResult.java
Метод	success() (строка 45–70)
Сейчас	ExecutionResult.success(...) → status = SUCCESS
Стало	Переименовать в filled(...) → status = FILLED. Убрать старый метод (или пометить @Deprecated на переходный период)
Причина	Название должно отражать семантику. Старый метод используется в BacktestExecutionEngine и BinanceExecutionEngine.verifyOrderInternal() — обновить все вызовы.
Зависит от	C1
C6 — BinanceExecutionEngine.verifyOrderInternal(): заменить SUCCESS-обёртку
Параметр	Значение
Файл	infrastructure/execution/binance/BinanceExecutionEngine.java
Метод	verifyOrderInternal() (строка 62–81)
Сейчас	При recoveryResult.isSuccess() оборачивает в ExecutionResult.success() с order.getPrice() вместо реальной цены fill
Стало	Пробрасывать recoveryResult как есть (после C3 он будет содержать правильный статус и fill данные). Не оборачивать.
Причина	Оборачивание подменяет реальный fill price ценой ордера. После C3 статус и поля уже будут корректными от mapStatusResponse().
Зависит от	C3
HIGH
Изменения, предотвращающие повреждение данных и гарантирующие консистентность, но не создающие немедленного финансового риска.

H1 — Order.java: добавить markAccepted(context, exchangeOrderId)
Параметр	Значение
Файл	domain/model/Order.java
Метод	новый markAccepted() (после строки 187)
Сейчас	Нет метода для перехода в SENT_TO_EXCHANGE
Стало	markAccepted(ExecutionContext, String exchangeOrderId) → OrderStatus.SENT_TO_EXCHANGE, записывает exchangeOrderId
Причина	SENT_TO_EXCHANGE определён в state graph (строка 38) и reconcilable (строка 151), но ни один producer не переводит ордер в это состояние. ACCEPTED из маппера должен переводить ордер в SENT_TO_EXCHANGE.
Зависит от	C4
H2 — OrderExecutionHandler.commitExecution(): добавить PARTIALLY_FILLED в idempotency guard
Параметр	Значение
Файл	application/service/execution/OrderExecutionHandler.java
Метод	commitExecution() (строка 162)
Сейчас	if (order.getStatus() == OrderStatus.FILLED) { return; }
Стало	if (order.getStatus() == OrderStatus.FILLED || order.getStatus() == OrderStatus.PARTIALLY_FILLED) { return; }
Причина	Retry после частичного fill не должен проходить в order.fill() и перезаписывать данные. Идемпотентность для PARTIALLY_FILLED должна блокировать повторный SUCCESS-вызов.
Зависит от	C4
H3 — BinanceExecutionAdapter.mapToExecutionResult(): добавить executedPrice
Параметр	Значение
Файл	infrastructure/binance/BinanceExecutionAdapter.java
Метод	mapToExecutionResult() (строка 156–161)
Сейчас	.executedQty(...) есть, .executedPrice(...) ОТСУТСТВУЕТ
Стало	.executedPrice(parsePrice(response)) — извлечь из response.get("price") или response.get("cummulativeQuoteQty") / executedQty
Причина	executedPrice = null приводит к INVARIANT-VIOLATION в resolveCompletionEventType(). Binance API возвращает цену во всех ответах.
Зависит от	C2
H4 — BinanceExecutionAdapter.mapStatusResponse(): добавить executedPrice
Параметр	Значение
Файл	infrastructure/binance/BinanceExecutionAdapter.java
Метод	mapStatusResponse() (строка 180–185)
Сейчас	.executedQty(...) есть, .executedPrice(...) ОТСУТСТВУЕТ
Стало	.executedPrice(response.getPrice()) — расширить OrderStatusResponse полем price
Причина	Аналогично H3 — при recovery нет цены fill.
Зависит от	C3
H5 — OrderStatusResponse: добавить поле price
Параметр	Значение
Файл	infrastructure/execution/binance/OrderStatusResponse.java
Метод	Класс (строка 7–17)
Сейчас	Поля: status, executedQty, exchangeOrderId, clientOrderId
Стало	Добавить BigDecimal price (или BigDecimal avgPrice)
Причина	Необходимо для H4. Binance API GET /api/v3/order возвращает поле price — среднюю цену исполнения.
Зависит от	—
H6 — ReconciliationService.syncOrderWithExchange(): обработать новые статусы
Параметр	Значение
Файл	application/service/risk/ReconciliationService.java
Метод	syncOrderWithExchange() (строка 202–219)
Сейчас	SUCCESS → forceFill(), REJECTED → markAsRejected() + release, CANCELED → markCancelled() + release
Стало	FILLED → forceFill(), PARTIALLY_FILLED → applyPartialFill(), ACCEPTED → ничего (живой ордер — ждём), REJECTED → markAsRejected() + release, CANCELED → markCancelled() + release, EXCHANGE_STATE_UNKNOWN → markAsUnknown()
Причина	После C3/C4 reconciliation получит правильные статусы. ACCEPTED не должен приводить к изменениям — ордер жив. PARTIALLY_FILLED должен вызывать applyPartialFill, а не forceFill.
Зависит от	C3, C4
MEDIUM
Изменения, улучшающие архитектуру и предотвращающие будущие ошибки, но не критические для текущей работы.

M1 — Создать BinanceStatusMapper
Параметр	Значение
Файл	новый: infrastructure/binance/BinanceStatusMapper.java
Метод	mapBinanceStatus(String), mapToAction(Status), mapToOrderStatus(Status), mapError(Exception)
Сейчас	Разрозненные маппинги в mapToExecutionResult() (строка 153), mapStatusResponse() (строка 179), mapErrorToResult() (строка 167), commitExecution() (строка 171)
Стало	Единый статический mapper. BinanceExecutionAdapter и commitExecution делегируют ему.
Причина	Шесть мест маппинга с разной логикой. Единый источник истины предотвращает расхождения.
Зависит от	C1–C4 (внедряется после стабилизации новых статусов)
M2 — BacktestExecutionEngine.verifyOrder(): убрать SUCCESS без данных
Параметр	Значение
Файл	infrastructure/execution/fake/BacktestExecutionEngine.java
Метод	verifyOrder() (строка 67–75)
Сейчас	ExecutionResult.builder().executedQty(BigDecimal.ZERO).status(SUCCESS).build() — SUCCESS с нулевым fill и без цены
Стало	ExecutionResult.builder().status(ACCEPTED).build() или возвращать FILLED с полными данными
Причина	Контракт SUCCESS/FILLED требует fill данных. Backtest verify симулирует успех без данных.
Зависит от	C1
M3 — ExecutionResult: переименовать executedQty → согласованное имя
Параметр	Значение
Файл	domain/model/ExecutionResult.java
Метод	Поле executedQty (строка 32) и executedPrice (строка 33)
Сейчас	executedQty / executedPrice
Стало	Без изменений. Имена корректны. Только добавить javadoc: «guaranteed non-null for FILLED and PARTIALLY_FILLED; may be null for ACCEPTED/REJECTED/CANCELED»
Причина	Документирование контракта для будущих разработчиков.
Зависит от	—
M4 — OrderStateTransitionPolicy.mapExecutionResult(): обновить или удалить
Параметр	Значение
Файл	domain/policy/OrderStateTransitionPolicy.java
Метод	mapExecutionResult() (строка 118–126)
Сейчас	SUCCESS → FILLED, REJECTED → REJECTED, CANCELED → CANCELED, TIMEOUT → UNKNOWN
Стало	Обновить под новые статусы (FILLED, ACCEPTED, PARTIALLY_FILLED, EXCHANGE_STATE_UNKNOWN) или удалить и заменить на BinanceStatusMapper.mapToOrderStatus()
Причина	Метод не используется в горячем пути, но должен быть консистентен с новым маппингом, если остаётся.
Зависит от	C1
M5 — ExecutionResult билдер: запретить прямой .status(FILLED) без данных
Параметр	Значение
Файл	domain/model/ExecutionResult.java
Метод	Класс
Сейчас	Lombok @Builder позволяет создать любой ExecutionResult с любым статусом
Стало	Добавить статический фабричный метод accepted(orderId, exchangeOrderId) и partiallyFilled(...). Закрыть билдер (@Builder(access = AccessLevel.PRIVATE)) или добавить кастомный build()-метод с валидацией: FILLED → executedQty ≠ null ∧ executedPrice ≠ null
Причина	Три места используют билдер в обход фабрик, создавая SUCCESS/FILLED без fill данных.
Зависит от	C1
M6 — OrderCompensationService.releasePartial(): интегрировать в flow
Параметр	Значение
Файл	application/risk/OrderCompensationService.java
Метод	releasePartial() (строка 20–33)
Сейчас	Метод существует, но не вызывается нигде в production-коде
Стало	Вызвать в commitExecution() для ACTION=CANCEL (освободить неисполненный остаток). Вызвать в ReconciliationService для CANCELED.
Причина	При отмене PARTIALLY_FILLED ордера нужно освободить только остаток, а не весь объём. Метод уже написан, но не используется.
Зависит от	C4, H6
Сводная таблица
ID	Severity	Файл	Метод/Область	Тип изменения
C1	CRITICAL	ExecutionResult.java	enum Status	Добавить 3 значения
C2	CRITICAL	BinanceExecutionAdapter.java	mapToExecutionResult()	Исправить условие success
C3	CRITICAL	BinanceExecutionAdapter.java	mapStatusResponse()	Исправить маппинг
C4	CRITICAL	OrderExecutionHandler.java	commitExecution()	Переписать switch
C5	CRITICAL	ExecutionResult.java	success() → filled()	Переименовать фабрику
C6	CRITICAL	BinanceExecutionEngine.java	verifyOrderInternal()	Убрать обёртку
H1	HIGH	Order.java	Новый markAccepted()	Добавить метод
H2	HIGH	OrderExecutionHandler.java	commitExecution() guard	Расширить guard
H3	HIGH	BinanceExecutionAdapter.java	mapToExecutionResult()	Добавить executedPrice
H4	HIGH	BinanceExecutionAdapter.java	mapStatusResponse()	Добавить executedPrice
H5	HIGH	OrderStatusResponse.java	Класс	Добавить поле price
H6	HIGH	ReconciliationService.java	syncOrderWithExchange()	Обработать новые статусы
M1	MEDIUM	Новый BinanceStatusMapper.java	Все методы	Создать mapper
M2	MEDIUM	BacktestExecutionEngine.java	verifyOrder()	Исправить статус
M3	MEDIUM	ExecutionResult.java	Поля	Добавить javadoc
M4	MEDIUM	OrderStateTransitionPolicy.java	mapExecutionResult()	Обновить/удалить
M5	MEDIUM	ExecutionResult.java	Builder	Закрыть/валидировать
M6	MEDIUM	OrderCompensationService.java	releasePartial()	Интегрировать в flow
Порядок внедрения
Этап 1 (C1-C6)  — Остановить кровотечение
├── C1: Новые статусы в ExecutionResult.Status
├── C2+C3: Исправить маппинги в BinanceExecutionAdapter
├── C4: Переписать commitExecution
├── C5: Переименовать фабрику, обновить вызовы
└── C6: Убрать verifyOrderInternal обёртку

Этап 2 (H1-H6)  — Закрыть дыры
├── H1: markAccepted() в Order
├── H2: PARTIALLY_FILLED guard
├── H3+H4+H5: executedPrice везде
└── H6: ReconciliationService обновить

Этап 3 (M1-M6)  — Архитектурная чистота
├── M1: BinanceStatusMapper
├── M2: Backtest фикс
├── M3+M4+M5: Документирование и защита
└── M6: releasePartial интеграция