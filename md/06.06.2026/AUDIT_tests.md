Полный анализ тестов ExecutionResult
1. ExecutionResult.Status — актуальный enum
   FILLED, PARTIALLY_FILLED, ACCEPTED, REJECTED, CANCELED, EXCHANGE_STATE_UNKNOWN
   НЕ существует: SUCCESS, TIMEOUT, FAILED_IO

2. ExecutionResult — фабрики и доступность builder
   Builder объявлен как @Builder(access = AccessLevel.PRIVATE) → ExecutionResult.builder() нельзя вызывать извне класса! Вызовы снаружи — compile-time ошибка.

Статические фабрики:

Фабрика	Сигнатура	Статус
filled(...)	10 параметров	✅
partiallyFilled(...)	7 параметров	✅
accepted(exchangeOrderId, clientOrderId)	2 параметра	✅
of(orderId, exchangeOrderId, executedQty, executedPrice, status, errorMessage)	6 параметров	✅
exchangeStateUnknown(orderId)	1 параметр	✅
rejected(orderId, errorMessage)	2 параметра	✅
canceled(orderId)	1 параметр	✅
failedIo(orderId, errorMessage)	2 параметра	✅
failure(orderId, errorMessage)	2 параметра	⚠️ @Deprecated
🔴 Тесты с ПРОБЛЕМАМИ (не скомпилируются / упадут)
A. Прямой вызов ExecutionResult.builder() + несуществующий Status.SUCCESS
Эти 4 теста имеют двойную проблему — и builder, и статус:

#	Файл	Класс	Метод	Проблема
1	src/test/java/.../risk/ReconciliationServiceIsolationTest.java:72-77	ReconciliationServiceIsolationTest	shouldAllowReconciliationWhenOrderIsUnknown()	builder() + Status.SUCCESS
2	src/test/java/.../risk/ReconciliationRecoveryIntegrationTest.java:66-71	ReconciliationRecoveryIntegrationTest	shouldRecoverStuckUnknownOrderToFilled()	builder() + Status.SUCCESS
3	src/test/java/.../execution/ExchangeTimeoutRecoveryTest.java:76-81	ExchangeTimeoutRecoveryTest	recoveryFromTimeoutTest()	builder() + Status.SUCCESS
4	src/test/java/.../execution/StaleExchangeStatusRecoveryTest.java:90-96	StaleExchangeStatusRecoveryTest	shouldRecoverFromStaleExchangeStatusWithoutDuplicatePlacement()	builder() + Status.SUCCESS
B. Несуществующий статус
#	Файл	Класс	Метод	Проблема
5	src/test/java/.../binance/BinanceMappingTest.java:56	BinanceMappingTest	testMappingTimeoutToTimeoutStatus()	Status.TIMEOUT — не существует
6	src/test/java/.../binance/BinanceClientOrderIdTest.java:45	BinanceClientOrderIdTest	shouldThrowExceptionWhenClientOrderIdIsMissing()	Status.FAILED_IO — не существует
C. Неверная сигнатура фабрики
#	Файл	Класс	Метод	Проблема
7	src/test/java/.../execution/ClaimVsWatchdogRaceTest.java:110-119	ClaimVsWatchdogRaceTest	watchdogRaceProtectionTest()	ExecutionResult.accepted(...) вызван с 8 параметрами, фабрика ожидает 2: (String exchangeOrderId, String clientOrderId)
🟡 Production callsites — покрытие тестами
Файл	Строка	Фабрика / Статус	Покрыт тестом?
BacktestExecutionEngine.java	50	filled(...) → FILLED	❌ нет
BacktestExecutionEngine.java	68	accepted(...) → ACCEPTED	❌ нет
BinanceExecutionAdapter.java	63	Status.REJECTED	✅ BinanceMappingTest
BinanceExecutionAdapter.java	79	failure(...) → EXCHANGE_STATE_UNKNOWN	❌ нет
BinanceExecutionAdapter.java	95	exchangeStateUnknown(...)	❌ нет
BinanceExecutionAdapter.java	149-151	of(...) → динамический	❌ нет
BinanceExecutionAdapter.java	163	of(...) → динамический	❌ нет
BinanceExecutionEngine.java	48	Status.EXCHANGE_STATE_UNKNOWN	❌ нет
BinanceExecutionEngine.java	68	filled(...) → FILLED	❌ нет
BinanceStatusMapper.java	83-88	mapBinanceStatus() — все статусы	❌ нет
BinanceStatusMapper.java	103-112	mapToAction() — все статусы	❌ нет
BinanceStatusMapper.java	117-126	mapToReconciliationAction() — все	❌ нет
BinanceStatusMapper.java	130-138	mapToOrderStatus() — все	❌ нет
BinanceStatusMapper.java	143-152	mapError() → rejected/exchangeStateUnknown/failedIo	❌ нет (кроме rejected через BinanceMappingTest)
OrderExecutionHandler.java	156-178	switch FILLED/PARTIALLY_FILLED/ACCEPTED/REJECTED/CANCELED/EXCHANGE_STATE_UNKNOWN	❌ все тесты сломаны
OrderExecutionHandler.java	193-209	resolveCompletionEventType()	❌ нет
ReconciliationService.java	195-226	switch FORCE_FILL/PARTIALLY_FILL/REJECT/CANCEL/MARK_UNKNOWN/NOOP	❌ все тесты сломаны
TradeService.java	35-113	onOrderFilled()	⚠️ частично (интеграционные)
🔧 Рекомендуемые исправления
📋 Таблица исправлений
#	testFile	testClass	testMethod	productionMethodsTested	usedStatuses	factoryUsage	problemDetected	recommendedFix
1	ReconciliationServiceIsolationTest.java‎
ReconciliationServiceIsolationTest	shouldAllowReconciliationWhenOrderIsUnknown()	ReconciliationService.syncOrderWithExchange()	SUCCESS (не сущ.)	ExecutionResult.builder()	true	Заменить на ExecutionResult.filled(orderId, "ex-123", "trade-123", "BTCUSDT", OrderSide.BUY, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, "USDT", "client-123"). Перенести orderId в arrange.
2	ReconciliationRecoveryIntegrationTest.java‎
ReconciliationRecoveryIntegrationTest	shouldRecoverStuckUnknownOrderToFilled()	ReconciliationService.syncOrderWithExchange()	SUCCESS (не сущ.)	ExecutionResult.builder()	true	Заменить на ExecutionResult.filled(orderId, "ex-123", "trade-123", "BTCUSDT", OrderSide.BUY, BigDecimal.ONE, BigDecimal.valueOf(50000), BigDecimal.ZERO, "USDT", "client-1")
3	ExchangeTimeoutRecoveryTest.java‎
ExchangeTimeoutRecoveryTest	recoveryFromTimeoutTest()	OrderExecutionHandler.consume(), OrderExecutionHandler.commitExecution()	SUCCESS (не сущ.)	ExecutionResult.builder()	true	Заменить на ExecutionResult.filled(orderId, "EX-123", "trade-123", "BTCUSDT", OrderSide.BUY, BigDecimal.ONE, new BigDecimal("50000"), BigDecimal.ZERO, "USDT", clientOrderId)
4	StaleExchangeStatusRecoveryTest.java‎
StaleExchangeStatusRecoveryTest	shouldRecoverFromStaleExchangeStatusWithoutDuplicatePlacement()	OrderExecutionHandler.consume(), OrderExecutionHandler.commitExecution()	SUCCESS (не сущ.)	ExecutionResult.builder()	true	Заменить на ExecutionResult.filled(orderId, "EX-123", "trade-123", "BTCUSDT", OrderSide.BUY, BigDecimal.ONE, new BigDecimal("50000"), BigDecimal.ZERO, "USDT", clientOrderId)
5	BinanceMappingTest.java‎
BinanceMappingTest	testMappingTimeoutToTimeoutStatus()	BinanceExecutionAdapter.fallbackPlaceOrder()	TIMEOUT (не сущ.)	—	true	Заменить ExecutionResult.Status.TIMEOUT на ExecutionResult.Status.EXCHANGE_STATE_UNKNOWN (timeout теперь маппится в EXCHANGE_STATE_UNKNOWN согласно BinanceStatusMapper.mapError())
6	BinanceClientOrderIdTest.java‎
BinanceClientOrderIdTest	shouldThrowExceptionWhenClientOrderIdIsMissing()	BinanceExecutionAdapter.placeOrder()	FAILED_IO (не сущ.)	—	true	Заменить ExecutionResult.Status.FAILED_IO на ExecutionResult.Status.EXCHANGE_STATE_UNKNOWN (согласно BinanceStatusMapper.mapError() → failedIo() → EXCHANGE_STATE_UNKNOWN). Или ExecutionResult.Status.REJECTED если так задумано.
7	ClaimVsWatchdogRaceTest.java‎
ClaimVsWatchdogRaceTest	watchdogRaceProtectionTest()	OrderExecutionHandler.consume(), OrderExecutionHandler.commitExecution()	ACCEPTED	ExecutionResult.accepted(...) с 8 аргументами	true	Заменить на ExecutionResult.accepted("EX123", "CL123"). Поле orderId отсутствует в accepted() — его нужно передать отдельно или использовать другую фабрику (of() с нужным статусом).
🕳️ GAPS — критические непокрытые области
Production callsites без тестов
Callsite	Статус	Компонент	Приоритет
ExecutionResult.filled(...)	FILLED	BacktestExecutionEngine, BinanceExecutionEngine	🔴 HIGH
ExecutionResult.partiallyFilled(...)	PARTIALLY_FILLED	Не используется в production (только объявлен)	🟡 MEDIUM
ExecutionResult.accepted(...)	ACCEPTED	BacktestExecutionEngine.verifyOrder()	🟡 MEDIUM
ExecutionResult.of(...)	динамический	BinanceExecutionAdapter (2 callsites)	🔴 HIGH
ExecutionResult.exchangeStateUnknown(...)	EXCHANGE_STATE_UNKNOWN	BinanceExecutionAdapter.getOrderStatus(), BinanceStatusMapper.mapError()	🔴 HIGH
ExecutionResult.canceled(...)	CANCELED	Не вызывается напрямую в production (только через mapper)	🟡 MEDIUM
ExecutionResult.failedIo(...)	EXCHANGE_STATE_UNKNOWN	BinanceStatusMapper.mapError()	🟡 MEDIUM
BinanceStatusMapper.mapBinanceStatus()	Все 6	BinanceExecutionAdapter	🔴 HIGH
BinanceStatusMapper.mapToAction()	Все 6	OrderExecutionHandler	🔴 HIGH
BinanceStatusMapper.mapToReconciliationAction()	Все 6	ReconciliationService	🔴 HIGH
OrderExecutionHandler.commitExecution() — CANCELED branch	CANCELED	OrderExecutionHandler	🟡 MEDIUM
OrderExecutionHandler.commitExecution() — REJECTED branch	REJECTED	OrderExecutionHandler	🟡 MEDIUM
OrderExecutionHandler.commitExecution() — EXCHANGE_STATE_UNKNOWN branch	EXCHANGE_STATE_UNKNOWN	OrderExecutionHandler	🔴 HIGH
BinanceExecutionEngine.execute()	FILLED + EXCHANGE_STATE_UNKNOWN	BinanceExecutionEngine	🔴 HIGH
BinanceExecutionEngine.verifyOrder()	all	BinanceExecutionEngine	🟡 MEDIUM
BinanceExecutionAdapter.getOrderStatus()	EXCHANGE_STATE_UNKNOWN	BinanceExecutionAdapter	🔴 HIGH
BinanceExecutionAdapter.mapToExecutionResult()	all	BinanceExecutionAdapter	🔴 HIGH
Критичные flows без тестов — сводка
Flow	Статус
BacktestExecutionEngine.verifyOrder()	❌ Нет теста
BinanceExecutionAdapter (прямые unit-тесты)	❌ Нет (только косвенно через BinanceMappingTest)
OrderExecutionHandler (валидные тесты)	❌ Все 3 теста сломаны
ReconciliationService (валидные тесты)	❌ 2 из 3 тестов сломаны
BinanceExecutionEngine	❌ Нет ни одного теста
BinanceStatusMapper	❌ Нет ни одного теста
TradeService	⚠️ Только интеграционные, без прямого unit
📦 Структурированный JSON
{
"analysisDate": "2026-06-06",
"enumDefinition": {
"enum": "ExecutionResult.Status",
"validStatuses": ["FILLED", "PARTIALLY_FILLED", "ACCEPTED", "REJECTED", "CANCELED", "EXCHANGE_STATE_UNKNOWN"],
"nonExistentUsedInTests": ["SUCCESS", "TIMEOUT", "FAILED_IO"]
},
"builderAccess": "PRIVATE (AccessLevel.PRIVATE)",
"tests": [
{
"testFile": "src/test/java/com/tradingbot/application/service/risk/ReconciliationServiceIsolationTest.java",
"testClass": "ReconciliationServiceIsolationTest",
"testMethod": "shouldAllowReconciliationWhenOrderIsUnknown",
"productionMethodsTested": ["ReconciliationService.syncOrderWithExchange"],
"usedStatuses": ["SUCCESS"],
"factoryUsage": "ExecutionResult.builder()",
"problemDetected": true,
"problemType": "DIRECT_BUILDER + INVALID_STATUS",
"recommendedFix": "Replace with ExecutionResult.filled(UUID, String, String, String, OrderSide, BigDecimal, BigDecimal, BigDecimal, String, String)"
},
{
"testFile": "src/test/java/com/tradingbot/application/service/risk/ReconciliationRecoveryIntegrationTest.java",
"testClass": "ReconciliationRecoveryIntegrationTest",
"testMethod": "shouldRecoverStuckUnknownOrderToFilled",
"productionMethodsTested": ["ReconciliationService.syncOrderWithExchange"],
"usedStatuses": ["SUCCESS"],
"factoryUsage": "ExecutionResult.builder()",
"problemDetected": true,
"problemType": "DIRECT_BUILDER + INVALID_STATUS",
"recommendedFix": "Replace with ExecutionResult.filled(orderId, \"ex-123\", \"trade-123\", \"BTCUSDT\", OrderSide.BUY, BigDecimal.ONE, BigDecimal.valueOf(50000), BigDecimal.ZERO, \"USDT\", \"client-1\")"
},
{
"testFile": "src/test/java/com/tradingbot/application/service/execution/ExchangeTimeoutRecoveryTest.java",
"testClass": "ExchangeTimeoutRecoveryTest",
"testMethod": "recoveryFromTimeoutTest",
"productionMethodsTested": ["OrderExecutionHandler.consume", "OrderExecutionHandler.commitExecution"],
"usedStatuses": ["SUCCESS"],
"factoryUsage": "ExecutionResult.builder()",
"problemDetected": true,
"problemType": "DIRECT_BUILDER + INVALID_STATUS",
"recommendedFix": "Replace with ExecutionResult.filled(orderId, \"EX-123\", \"trade-123\", \"BTCUSDT\", OrderSide.BUY, BigDecimal.ONE, new BigDecimal(\"50000\"), BigDecimal.ZERO, \"USDT\", clientOrderId)"
},
{
"testFile": "src/test/java/com/tradingbot/application/service/execution/StaleExchangeStatusRecoveryTest.java",
"testClass": "StaleExchangeStatusRecoveryTest",
"testMethod": "shouldRecoverFromStaleExchangeStatusWithoutDuplicatePlacement",
"productionMethodsTested": ["OrderExecutionHandler.consume", "OrderExecutionHandler.commitExecution"],
"usedStatuses": ["SUCCESS"],
"factoryUsage": "ExecutionResult.builder()",
"problemDetected": true,
"problemType": "DIRECT_BUILDER + INVALID_STATUS",
"recommendedFix": "Replace with ExecutionResult.filled(orderId, \"EX-123\", \"trade-123\", \"BTCUSDT\", OrderSide.BUY, BigDecimal.ONE, new BigDecimal(\"50000\"), BigDecimal.ZERO, \"USDT\", clientOrderId)"
},
{
"testFile": "src/test/java/com/tradingbot/infrastructure/exchange/binance/BinanceMappingTest.java",
"testClass": "BinanceMappingTest",
"testMethod": "testMappingTimeoutToTimeoutStatus",
"productionMethodsTested": ["BinanceExecutionAdapter.fallbackPlaceOrder"],
"usedStatuses": ["TIMEOUT"],
"factoryUsage": "none",
"problemDetected": true,
"problemType": "INVALID_STATUS",
"recommendedFix": "Replace ExecutionResult.Status.TIMEOUT with ExecutionResult.Status.EXCHANGE_STATE_UNKNOWN"
},
{
"testFile": "src/test/java/com/tradingbot/infrastructure/exchange/binance/BinanceClientOrderIdTest.java",
"testClass": "BinanceClientOrderIdTest",
"testMethod": "shouldThrowExceptionWhenClientOrderIdIsMissing",
"productionMethodsTested": ["BinanceExecutionAdapter.placeOrder"],
"usedStatuses": ["FAILED_IO"],
"factoryUsage": "none",
"problemDetected": true,
"problemType": "INVALID_STATUS",
"recommendedFix": "Replace ExecutionResult.Status.FAILED_IO with ExecutionResult.Status.EXCHANGE_STATE_UNKNOWN or REJECTED"
},
{
"testFile": "src/test/java/com/tradingbot/application/service/execution/ClaimVsWatchdogRaceTest.java",
"testClass": "ClaimVsWatchdogRaceTest",
"testMethod": "watchdogRaceProtectionTest",
"productionMethodsTested": ["OrderExecutionHandler.consume", "OrderExecutionHandler.commitExecution"],
"usedStatuses": ["ACCEPTED"],
"factoryUsage": "ExecutionResult.accepted(UUID, String, String, null, null, OrderSide, BigDecimal, BigDecimal) [WRONG SIGNATURE]",
"problemDetected": true,
"problemType": "WRONG_FACTORY_SIGNATURE",
"recommendedFix": "Replace with ExecutionResult.accepted(\

ты подвис
Думал 1 сек.
Продолжаю с того места, где оборвалось.

Завершаю строку JSON для ClaimVsWatchdogRaceTest:

"recommendedFix": "Replace with ExecutionResult.accepted(\"EX123\", \"CL123\"). Use ExecutionResult.of(orderId, \"EX123\", BigDecimal.TEN, BigDecimal.ZERO, Status.ACCEPTED, null) if orderId is needed"
Сводная таблица проблемных тестов
#	testFile (сокращённо)	testMethod	Проблема	Исправление
1	.../risk/ReconciliationServiceIsolationTest.java	shouldAllowReconciliationWhenOrderIsUnknown	builder() + Status.SUCCESS	ExecutionResult.filled(...)
2	.../risk/ReconciliationRecoveryIntegrationTest.java	shouldRecoverStuckUnknownOrderToFilled	builder() + Status.SUCCESS	ExecutionResult.filled(...)
3	.../execution/ExchangeTimeoutRecoveryTest.java	recoveryFromTimeoutTest	builder() + Status.SUCCESS	ExecutionResult.filled(...)
4	.../execution/StaleExchangeStatusRecoveryTest.java	shouldRecoverFromStaleExchangeStatus...	builder() + Status.SUCCESS	ExecutionResult.filled(...)
5	.../binance/BinanceMappingTest.java	testMappingTimeoutToTimeoutStatus	Status.TIMEOUT	Status.EXCHANGE_STATE_UNKNOWN
6	.../binance/BinanceClientOrderIdTest.java	shouldThrowExceptionWhenClientOrderIdIsMissing	Status.FAILED_IO	Status.EXCHANGE_STATE_UNKNOWN
7	.../execution/ClaimVsWatchdogRaceTest.java	watchdogRaceProtectionTest	accepted() с 8 параметрами	accepted("EX123","CL123") или of(...)
🕳️ GAPS — критические callsites без тестов
Компонент	Callsite	Статус	Приоритет
BinanceExecutionEngine	execute() → filled()	FILLED	🔴
BinanceExecutionEngine	execute() → Status.EXCHANGE_STATE_UNKNOWN ветка	EXCHANGE_STATE_UNKNOWN	🔴
BinanceExecutionEngine	verifyOrder()	все	🔴
BinanceExecutionAdapter	getOrderStatus() → exchangeStateUnknown()	EXCHANGE_STATE_UNKNOWN	🔴
BinanceExecutionAdapter	doPlaceOrder() → of() (2 callsites)	динамический	🔴
BinanceStatusMapper	mapBinanceStatus()	все 6 статусов	🔴
BinanceStatusMapper	mapToAction()	все 6 статусов	🔴
BinanceStatusMapper	mapToReconciliationAction()	все 6 статусов	🔴
BinanceStatusMapper	mapToOrderStatus()	все 6 статусов	🔴
BinanceStatusMapper	mapError() → rejected/exchangeStateUnknown/failedIo	REJECTED + EXCHANGE_STATE_UNKNOWN	🟡
BacktestExecutionEngine	execute() → filled()	FILLED	🟡
BacktestExecutionEngine	verifyOrder() → accepted()	ACCEPTED	🟡
OrderExecutionHandler	commitExecution() → CANCELED branch	CANCELED	🟡
OrderExecutionHandler	commitExecution() → REJECTED branch	REJECTED	🟡
OrderExecutionHandler	commitExecution() → EXCHANGE_STATE_UNKNOWN branch	EXCHANGE_STATE_UNKNOWN	🔴
OrderExecutionHandler	resolveCompletionEventType()	все	🟡
Всего: 7 тестов падают, 0 тестов покрывают критические компоненты (BinanceExecutionEngine, BinanceStatusMapper, BinanceExecutionAdapter — тестов нет вообще).