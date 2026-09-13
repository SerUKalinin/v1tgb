POST-FIX EXECUTION DATA FLOW AUDIT
1. Все места создания ExecutionResult.success(...)
   1A. Через статический фабричный метод ExecutionResult.success(...)
   Место №1: BacktestExecutionEngine.execute()
   Файл: BacktestExecutionEngine.java‎
   Строки: 52–63
   Поля:
   exchangeOrderId → externalOrderId (fake-order-...)
   executedQuantity → order.getQuantity() ✅
   averagePrice → order.getPrice() ✅
   status → SUCCESS
   Место №2: BinanceExecutionEngine.verifyOrderInternal()
   Файл: BinanceExecutionEngine.java‎
   Строки: 67–78
   Поля:
   exchangeOrderId → recoveryResult.getExchangeOrderId()
   executedQuantity → recoveryResult.getExecutedQty() ⚠️ (зависит от mapStatusResponse)
   averagePrice → order.getPrice() ⚠️ (цена ордера, НЕ fill price!)
   status → SUCCESS
   Место №3: BinanceExecutionEngine.mapToResult()
   Файл: BinanceExecutionEngine.java‎
   Строки: 83–95
   Поля:
   exchangeOrderId → entity.getExchangeOrderId()
   executedQuantity → entity.getQuantity()
   averagePrice → entity.getPrice()
   status → SUCCESS
   1B. Через прямой билдер .status(SUCCESS) (в обход фабрики)
   Место №4: BinanceExecutionAdapter.mapToExecutionResult()
   Файл: BinanceExecutionAdapter.java‎
   Строки: 155–161
   Поля:
   exchangeOrderId → response.get("orderId").toString()
   executedQuantity → new BigDecimal((String) response.get("executedQty"))
   executedPrice — ОТСУТСТВУЕТ (null) 🔴
   status → SUCCESS
   Место №5: BinanceExecutionAdapter.mapStatusResponse()
   Файл: BinanceExecutionAdapter.java‎
   Строки: 178–185
   Поля:
   exchangeOrderId → response.getExchangeOrderId()
   executedQuantity → response.getExecutedQty()
   executedPrice — ОТСУТСТВУЕТ (null) 🔴
   status → SUCCESS (при статусе биржи FILLED)
   Место №6: BacktestExecutionEngine.verifyOrder()
   Файл: BacktestExecutionEngine.java‎
   Строки: 70–74
   Поля:
   exchangeOrderId → "fake-recon-" + clientOrderId
   executedQuantity → BigDecimal.ZERO
   executedPrice — ОТСУТСТВУЕТ (null) 🔴
   status → SUCCESS
2. Все реализации ExecutionPort.execute() / ExecutionEngine.execute()
   Реализация A: BinanceExecutionEngine
   Файл: BinanceExecutionEngine.java‎
   Строка: 31 — метод execute(Order order)
   Как извлекается fill:
   Проверяет БД на существующий ордер (orderRepository.findByClientOrderId). Если найден и уже обработан → возвращает mapToResult() (строки 83–95), где fill данные = entity.getQuantity() / entity.getPrice() из БД.
   Иначе вызывает executionPort.placeOrder(order) (строка 47) — делегирует в BinanceExecutionAdapter.
   При таймауте → вызывает verifyOrderInternal() (строка 62), который через executionPort.getOrderStatus() получает recoveryResult и оборачивает его через ExecutionResult.success().
   Реализация B: BacktestExecutionEngine
   Файл: BacktestExecutionEngine.java‎
   Строка: 30 — метод execute(Order order)
   Как извлекается fill: fill = данные из самого ордера: order.getQuantity() / order.getPrice(). Это симуляция.
   Адаптер: BinanceExecutionAdapter (реализует ExecutionPort)
   Файл: BinanceExecutionAdapter.java‎
   Строка: 36 — метод placeOrder(Order order) → делегирует в doPlaceOrder() (строка 42)
   Строка: 82 — метод getOrderStatus(String clientOrderId) (строка 82)
   Как извлекается fill:
   placeOrder → ответ от Binance API POST /api/v3/order → mapToExecutionResult() (строка 151)
   getOrderStatus → ответ от Binance API GET /api/v3/order → mapStatusResponse() (строка 178)
3. Полный код BinanceExecutionAdapter
   // placeOrder (строка 36)
   public ExecutionResult placeOrder(Order order) {
   return self.doPlaceOrder(order);
   }

// doPlaceOrder (строки 42-67)
@CircuitBreaker(name = "exchangeExecution", fallbackMethod = "fallbackPlaceOrder")
@Retry(name = "exchangeExecution")
public ExecutionResult doPlaceOrder(Order order) {
Map<String, String> params = new HashMap<>();
params.put("symbol", order.getSymbol());
params.put("side", order.getSide().name());
params.put("type", order.getType().name());
params.put("quantity", normalizeQuantity(order.getQuantity()));
params.put("newClientOrderId", sanitizeClientId(order.getClientOrderId()));
if ("LIMIT".equals(order.getType().name())) {
params.put("price", normalizePrice(order.getPrice()));
params.put("timeInForce", "GTC");
}
try {
Map response = binanceClient.post("/api/v3/order", params, Map.class, true);
return mapToExecutionResult(order, response);  // ← строка 58
} catch (Exception e) {
...
}
}

// mapToExecutionResult (строки 151-165)
private ExecutionResult mapToExecutionResult(Order order, Map response) {
String status = (String) response.get("status");
boolean success = "FILLED".equals(status) || "NEW".equals(status) || "PARTIALLY_FILLED".equals(status);
if (success) {
return ExecutionResult.builder()
.orderId(order.getId())
.exchangeOrderId(response.get("orderId").toString())
.executedQty(new BigDecimal((String) response.get("executedQty")))
.status(ExecutionResult.Status.SUCCESS)
// ⚠️ executedPrice НЕ УСТАНОВЛЕН — будет null
.build();
} else {
return ExecutionResult.rejected(order.getId(), status);
}
}

// mapStatusResponse (строки 178-185)
private ExecutionResult mapStatusResponse(OrderStatusResponse response) {
boolean success = "FILLED".equals(response.getStatus());
return ExecutionResult.builder()
.exchangeOrderId(response.getExchangeOrderId())
.executedQty(response.getExecutedQty())
.status(success ? ExecutionResult.Status.SUCCESS : ExecutionResult.Status.REJECTED)
// ⚠️ executedPrice НЕ УСТАНОВЛЕН — будет null
.errorMessage(success ? null : response.getStatus())
.build();
}
4. Пути где SUCCESS возвращается без данных
   🔴 Нарушение №1: BinanceExecutionAdapter.mapToExecutionResult() — строка 156–161
   // строка 156
   return ExecutionResult.builder()
   .orderId(order.getId())
   .exchangeOrderId(response.get("orderId").toString())
   .executedQty(new java.math.BigDecimal((String) response.get("executedQty")))
   .status(ExecutionResult.Status.SUCCESS)
   .build();
   // ⚠️ .executedPrice(...)  — НЕ ВЫЗВАН → executedPrice == null
   🔴 Нарушение №2: BinanceExecutionAdapter.mapStatusResponse() — строка 180–185
   // строка 180
   return ExecutionResult.builder()
   .exchangeOrderId(response.getExchangeOrderId())
   .executedQty(response.getExecutedQty())
   .status(success ? ExecutionResult.Status.SUCCESS : ExecutionResult.Status.REJECTED)
   .errorMessage(success ? null : response.getStatus())
   .build();
   // ⚠️ .executedPrice(...)  — НЕ ВЫЗВАН → executedPrice == null
   🔴 Нарушение №3: BacktestExecutionEngine.verifyOrder() — строка 70–74
   // строка 70
   return ExecutionResult.builder()
   .exchangeOrderId("fake-recon-" + clientOrderId)
   .executedQty(BigDecimal.ZERO)
   .status(ExecutionResult.Status.SUCCESS)
   .build();
   // ⚠️ .executedPrice(...)  — НЕ ВЫЗВАН → executedPrice == null
   🟡 Опосредованное нарушение: BinanceExecutionEngine.verifyOrderInternal() — строка 67
   recoveryResult.getExecutedQty()   // ← приходит из mapStatusResponse(), где executedPrice = null
   order.getPrice()                   // ← это цена ордера, НЕ фактическая цена fill
   Здесь averagePrice заполняется ценой ордера (order.getPrice()), а не реальной ценой исполнения с биржи.

5. Контракт SUCCESS → fill data
   Код, подтверждающий ожидаемый контракт:
   OrderExecutionHandler.resolveCompletionEventType() — строка 206–213:

if (result.getStatus() == ExecutionResult.Status.SUCCESS) {
boolean hasRealExecution = order.getExecutedQuantity() != null
&& order.getAveragePrice() != null;
if (!hasRealExecution) {
log.error("[INVARIANT-VIOLATION] SUCCESS result but no execution data. orderId={}, status={}",
order.getId(), order.getStatus());
}
return hasRealExecution ? "ORDER_EXECUTED" : "ORDER_COMPLETED";
}
Этот код доказывает, что контракт уже нарушается в рантайме — система ожидает и логирует INVARIANT-VIOLATION.

Order.fill() — строка 189:

public void fill(ExecutionContext context, String exchangeOrderId,
BigDecimal executedQty, BigDecimal executedPrice) {
this.executedQuantity = executedQty;   // может стать null
this.averagePrice = executedPrice;     // может стать null
...
}
Метод не проверяет входные параметры на null — пропускает их как есть.

6. Финальный вывод
   🔴 Вариант B: SUCCESS может возвращаться без fill data
   Место	Файл	Строки	Что пропущено
   BinanceExecutionAdapter.mapToExecutionResult()	BinanceExecutionAdapter.java	156–161	executedPrice = null
   BinanceExecutionAdapter.mapStatusResponse()	BinanceExecutionAdapter.java	180–185	executedPrice = null
   BacktestExecutionEngine.verifyOrder()	BacktestExecutionEngine.java	70–74	executedPrice = null
   Корневая причина
   Статический фабричный метод ExecutionResult.success(...) требует executedQty + executedPrice и гарантирует контракт. Но три места в коде используют прямой ExecutionResult.builder()...status(SUCCESS).build() в обход фабрики, не устанавливая executedPrice.

Impact
Order.executedPrice (averagePrice) становится null в БД
resolveCompletionEventType падает в ветку "ORDER_COMPLETED" вместо "ORDER_EXECUTED", что ломает downstream-обработку
BinanceExecutionEngine.verifyOrderInternal() подменяет реальный fill price на цену ордера (order.getPrice())
Рекомендация
Добавить executedPrice в mapToExecutionResult() и mapStatusResponse() — Binance API возвращает поле price / avgPrice / cummulativeQuoteQty в ответе.
В BacktestExecutionEngine.verifyOrder() либо пробросить цену, либо использовать не-SUCCESS статус.
Запретить использование прямого билдера для SUCCESS — либо сделать конструктор @Deprecated, либо добавить валидацию в @Builder билдере.