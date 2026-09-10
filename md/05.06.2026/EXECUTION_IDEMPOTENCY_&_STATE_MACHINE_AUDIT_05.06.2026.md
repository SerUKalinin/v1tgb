[CRITICAL-1] Order не хранит lastExecutionId — domain-слой не имеет executionId guard
FILE: src/main/java/com/tradingbot/domain/model/Order.java:39 METHOD: Class definition ISSUE:

// Order.java — строки 38-45
private UUID executionId;           // ← есть
// private UUID lastExecutionId;    // ← ОТСУТСТВУЕТ в domain-модели
private Instant executionStartedAt;
private int executionAttempts = 0;
OrderEntity (строка 78) имеет поле lastExecutionId, но Order domain-модель — нет. Order.reconstruct() (строка 116-122) не принимает lastExecutionId. OrderMapper.updateEntity() (строка 70-82) не копирует lastExecutionId. OrderMapper.toDomain() (строка 19-40) не передаёт в reconstruct.

WHY IT BREAKS IDEMPOTENCY: Без lastExecutionId domain-слой не может различить «это тот же executionId повторно» от «это новый executionId». fill() не может сделать NOOP по executionId.

EXPECTED FIX: Добавить поле lastExecutionId в Order, параметр в reconstruct(), маппинг в OrderMapper.

[CRITICAL-2] fill() не проверяет lastExecutionId — повторный вызов того же события не делает NOOP
FILE: src/main/java/com/tradingbot/domain/model/Order.java:173-178 METHOD: fill(ExecutionContext context, String exchangeOrderId, BigDecimal executedQty, BigDecimal executedPrice) ISSUE:

public void fill(ExecutionContext context, String exchangeOrderId, ...) {
OrderStateTransitionPolicy.validateAndPassThrough(context, this.status, OrderStatus.FILLED);
// ⬆ проверяет ТОЛЬКО state transition, не проверяет executionId
this.exchangeOrderId = exchangeOrderId;
this.executedQuantity = executedQty;
this.averagePrice = executedPrice;
this.status = OrderStatus.FILLED;
// НЕТ: this.lastExecutionId = context.attempt().executionId();
}
Current guard: только current == target → return context (однострочная проверка в validateAndPassThrough). Нет guard-а: «если lastExecutionId совпадает → не менять state».

WHAT HAPPENS AT RETRY — trace:

Commit TX2: fill() → status=EXECUTING→FILLED, executionId=X, lastExecutionId=null
↓
clearExecutionOwner() → executionId=null (in-memory)
↓
save() → updateEntity НЕ копирует executionId → entity.executionId остаётся в БД???
Стоп. OrderEntity.executionId имеет @Setter(AccessLevel.NONE) (строка 75). updateEntity() НЕ вызывает entity.setExecutionId(). Значит executionId в БД никогда не обновляется после toEntity().

Если ORDER_EXECUTED outbox event ретраится и попадает в OrderExecutedEventHandler:

// OrderExecutedEventHandler.java:68-69
if (targetStatus == OrderStatus.FILLED) {
order.fill(context, executedQty, executionPrice);
}
Поведение: status=FILLED, target=FILLED → current==target → return context → NOOP. ✅ Не бросает exception. Но не проверяет, тот ли это executionId.

WHY IT BREAKS IDEMPOTENCY: Domain-слой не имеет executionId-aware guard. Он полагается только на state-machine (FILLED→FILLED = NOOP), что работает для terminal state, но НЕ работает для промежуточных состояний. Если бы злоумышленник / баг вызвал fill() из RECOVERING (разрешено графом), то повторный вызов с тем же executionId произведёт повторную мутацию.

EXPECTED FIX:

public void fill(ExecutionContext context, ...) {
UUID incomingExecutionId = context.attempt().executionId();
// guard 1: same executionId = NOOP
if (incomingExecutionId.equals(this.lastExecutionId)) return;
// guard 2: already FILLED = NOOP
if (this.status == OrderStatus.FILLED) return;
// guard 3: state transition validation
OrderStateTransitionPolicy.validateAndPassThrough(context, this.status, OrderStatus.FILLED);
// mutation
this.exchangeOrderId = exchangeOrderId;
this.executedQuantity = executedQty;
this.averagePrice = executedPrice;
this.status = OrderStatus.FILLED;
this.lastExecutionId = incomingExecutionId;
}
[HIGH-3] OrderEntity.executionId защищён @Setter(NONE) — не обновляется через updateEntity
FILE: src/main/java/com/tradingbot/infrastructure/persistence/entity/OrderEntity.java:74-76 METHOD: Field definition ISSUE:

@Column(name = "execution_id")
@Setter(AccessLevel.NONE)    // ← SETTER ОТСУТСТВУЕТ
private UUID executionId;
OrderMapper.updateEntity() (строка 70-82) не копирует executionId и не может — сеттера нет. Полный список копируемых полей из updateEntity:

entity.setStatus()              ✅
entity.setUpdatedAt()           ✅
entity.setExchangeOrderId()     ✅
entity.setExecutionStartedAt()  ✅
entity.setExecutionAttempts()   ✅
entity.setExecutedQuantity()    ✅
entity.setAveragePrice()        ✅
entity.setRejectionReason()     ✅
entity.setExecutionId()         ❌ НЕТ (сеттер заблокирован)
entity.setLastExecutionId()     ❌ НЕТ
Это значит:

assignExecutionOwner() присваивает executionId в in-memory Order
После save() в БД execution_id остаётся null (или значение из toEntity при создании)
clearExecutionOwner() обнуляет in-memory, но БД не меняется (и так null)
WHY IT BREAKS IDEMPOTENCY: executionId — ключ идемпотентности — не синхронизирован между domain и persistence. При любой перезагрузке из БД executionId будет null. Это создаёт расхождение: in-memory Order думает, что executionId=X, а БД говорит null.

EXPECTED FIX: Убрать @Setter(AccessLevel.NONE) с executionId и lastExecutionId, добавить маппинг в updateEntity().

[HIGH-4] OrderMapper НЕ копирует lastExecutionId ни в одну сторону
FILE: src/main/java/com/tradingbot/infrastructure/persistence/mapper/OrderMapper.java:19-40, 43-67, 70-82 METHOD: toDomain(), toEntity(), updateEntity() ISSUE:

toDomain() (строка 19-40) — не передаёт lastExecutionId в Order.reconstruct():

return Order.reconstruct(
entity.getId(), entity.getClientOrderId(), ...,
entity.getExecutionId(),      // ← executionId: есть
// entity.getLastExecutionId() ← ОТСУТСТВУЕТ
entity.getExecutionStartedAt(), ...
);
toEntity() (строка 43-67) — не копирует lastExecutionId в builder:

.executionId(order.getExecutionId())
// .lastExecutionId(order.getLastExecutionId()) ← ОТСУТСТВУЕТ
updateEntity() (строка 70-82) — не копирует lastExecutionId в entity:

entity.setStatus(order.getStatus());
// entity.setLastExecutionId(order.getLastExecutionId()); ← ОТСУТСТВУЕТ
WHY IT BREAKS IDEMPOTENCY: lastExecutionId объявлен в OrderEntity (строка 78), но не существует в domain-модели и не мапится. Это dead data. При перезагрузке из БД lastExecutionId теряется — retry не может быть обнаружен.

EXPECTED FIX: Добавить параметр lastExecutionId в Order.reconstruct(), добавить маппинг во всех трёх методах OrderMapper.

[HIGH-5] commitExecution вызывает fill() до clearExecutionOwner() — обратный порядок
FILE: src/main/java/com/tradingbot/application/service/execution/OrderExecutionHandler.java:165-180 METHOD: commitExecution() ISSUE:

// Строки 165-181:
if (result.getStatus() == ExecutionResult.Status.SUCCESS) {
order.fill(context, ...);     // 1. статус → FILLED
}
// ...
if (OrderStateTransitionPolicy.isTerminal(order.getStatus())) {
order.clearExecutionOwner();  // 2. executionId → null
}
orderRepository.save(order);      // 3. save
Порядок: fill() → clearExecutionOwner() → save().

В случае retry того же ORDER_CREATED события из Outbox:

executionClaimPort.existsByExecutionId() → TRUE → claimOrder возвращает empty → consume завершается → NOOP ✅
Но если retry обойдёт claimOrder (гипотетический баг) и попадёт напрямую в commitExecution:

validateExecutionOwnership() проверит terminal status → бросит ExecutionOwnershipException ❌
WHY IT BREAKS IDEMPOTENCY: Domain-слой не защищён от вызова fill() на уже FILLED ордере независимо от application-level guard. Правило «at-least-once delivery = domain must be idempotent» нарушено.

EXPECTED FIX: fill() сам должен возвращать NOOP при status == FILLED без exception.

[MEDIUM-6] ExecutionOwnershipValidator бросает exception на terminal state вместо NOOP
FILE: src/main/java/com/tradingbot/domain/execution/ExecutionOwnershipValidator.java:24-26 METHOD: validateExecutionOwnership() ISSUE:

if (OrderStateTransitionPolicy.isTerminal(order.getStatus())) {
throw new ExecutionOwnershipException(String.format(
"Order %s already terminal (%s)", order.getId(), order.getStatus()));
}
Этот validator вызывается в commitExecution() (строка 159) перед fill(). При нормальном flow ордер в EXECUTING — проверка проходит. Но если retry somehow обходит claimOrder и попадает в commitExecution (после того как TX2 первого вызова закоммитил FILLED) — будет exception вместо NOOP.

WHY IT BREAKS IDEMPOTENCY: Для idempotent replay допустимо получить