Static Analysis Report: Order Execution Pipeline
A. Order.fill() — Primary Mutation
Файл	Order.java:181-197
Guard Matrix
Guard	Строка	Первый fill	Retry fill	Оценка
status == FILLED	183	⏭ пропускает	✅ NOOP	✅
execId != null && execId == context.execId()	187	❌ срабатывает	✅ NOOP	❌
validateAndPassThrough	191	✅ EXECUTING→FILLED разрешён	—	✅
Корень проблемы
// claim phase (OrderRepositoryAdapter:55-56)
order.assignExecutionOwner(X);   // this.executionId = X ← ПРИСВОЕН
order.markExecuting(context);    // this.status = EXECUTING

// commit phase (OrderExecutionHandler:172)
order.fill(context, X, qty, price);
// → Order.java:187: this.executionId(X) == context.execId(X) → return ← БЛОКИРОВАН
Состояние PENDING_EXECUTION → EXECUTING → ⛔ FILLED. Ордер навсегда EXECUTING.

Call Sites затронуты
Call Site	Файл:строка	Результат
commitExecution → fill	OEH:172	Заблокирован
Reconciliation → fill	RecSvc:204	Заблокирован
OrderExecutedEventHandler → fill	EventHandler:69	Заблокирован
[CRITICAL] Исправление Order.java:187
-        if (this.executionId != null && this.executionId.equals(context.attempt().executionId())) {
+        if (this.executionId != null
+            && this.executionId.equals(context.attempt().executionId())
+            && this.status == OrderStatus.FILLED) {
После: первый fill (status=EXECUTING) проходит ✅, retry fill (status=FILLED) → строка 183 возвращает ✅.

B. applyPartialFill() — Нет Idempotency
Файл:строка	Order.java:204-208
public void applyPartialFill(...) {
OrderStateTransitionPolicy.validateAndPassThrough(context, this.status, OrderStatus.PARTIALLY_FILLED);
this.executedQuantity = qty;   // ← перезаписывается при retry
this.averagePrice = price;     // ← перезаписывается при retry
this.status = OrderStatus.PARTIALLY_FILLED;
}
[HIGH] Исправление
public void applyPartialFill(ExecutionContext context, BigDecimal qty, BigDecimal price) {
+        if (this.status == OrderStatus.PARTIALLY_FILLED) {
+            return;
+        }
  OrderStateTransitionPolicy.validateAndPassThrough(context, this.status, OrderStatus.PARTIALLY_FILLED);
  C. markRecovering(), markAsRejected(), markCancelled(), markAsUnknown() — Retry re-assign
  Метод	Строка	Mutation на retry	Severity
  markRecovering	210-213	this.status = RECOVERING	MEDIUM
  markAsRejected	215-219	this.status, rejectionReason	MEDIUM
  markCancelled	221-224	this.status = CANCELED	MEDIUM
  markAsUnknown	226-229	this.status = UNKNOWN	MEDIUM
  validateAndPassThrough возвращает при current==target, но присваивание всё равно происходит. Не ломает логику, но не чисто.

[MEDIUM] Исправление
public void markRecovering(ExecutionContext context) {
+        if (this.status == OrderStatus.RECOVERING) return;
  OrderStateTransitionPolicy.validateAndPassThrough(context, this.status, OrderStatus.RECOVERING);
  Аналогично для остальных трёх.

D. OrderExecutedEventHandler — Event ID, не ExecutionID
Файл:строка	OrderExecutedEventHandler.java:45
if (idempotencyService.isAlreadyProcessed(event.getId())) { return; }
Использует entity PK, а не executionId. Если outbox породит новый event с тем же executionId — защита не сработает.

[MEDIUM] Рекомендация
Заменить проверку на event.getExecutionId() или убрать (fill-guard в Order.java уже защищает).

E. clearExecutionOwner() — Теряет executionId после FILLED
| Файл:строка | OrderExecutionHandler.java:185-186, RecSvc:233-234 |

order.clearExecutionOwner();  // this.executionId = null
orderRepository.save(order);  // сохраняет null в БД
[MEDIUM]
Смягчено: commitExecution:162 guard FILLED → return. Но ReconciliationService сохраняет null. При повторном reconciliation: ExecutionOwnershipValidator:19 → "was never claimed" → exception.

F. Domain ↔ Entity Synchronization — PASS ✅
Слой	Файл:строка	Поле
updateEntity	OrderMapper:76	entity.setExecutionId(...) ✅
toDomain	OrderMapper:33	entity.getExecutionId() → reconstruct() ✅
toEntity	OrderMapper:60	.executionId(order.getExecutionId()) ✅
Entity setter	OrderEntity:75	private UUID executionId (setter unlocked) ✅
lastExecutionId	—	Отсутствует ✅
G. Race Conditions — PASS ✅
Сценарий	Защита	Файл
Два consumer на один order	findByIdForUpdate (pessimistic)	OrderRepositoryAdapter:37
Reconciliation параллельно commit	claimForReconciliation checks EXECUTING + stale	RecSvc:175
Dirty write	@Version + OptimisticLockException	OrderEntity:94
Итог: Все Violations
#	Severity	Файл:Строка	Тип	Fix
1	CRITICAL	Order.java:187	Block first fill	Добавить && this.status == OrderStatus.FILLED
2	HIGH	Order.java:204	No idempotency	if (status == PARTIALLY_FILLED) return;
3	MEDIUM	Order.java:210	Reassign on retry	if (status == RECOVERING) return;
4	MEDIUM	Order.java:215	Reassign on retry	if (status == REJECTED) return;
5	MEDIUM	Order.java:221	Reassign on retry	if (status == CANCELED) return;
6	MEDIUM	Order.java:226	Reassign on retry	if (status == UNKNOWN) return;
7	MEDIUM	EventHandler:45	Event ID, не executionId	Сменить на executionId-based
8	MEDIUM	OEH:185, RecSvc:233	Теряет executionId	Не обнулять, либо guard перед check