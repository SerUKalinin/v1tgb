EXECUTION STATE MACHINE AUDIT
SECTION 1 — STATE MACHINE
OrderStatus enum (OrderStatus.java‎
)
NEW                 // ls=6
VALIDATED           // ls=7
PENDING_EXECUTION   // ls=8 — Ожидает асинхронного исполнения
EXECUTING           // ls=9 — В процессе исполнения (атомарный захват)
SENT                // ls=10
SENT_TO_EXCHANGE    // ls=11
PARTIALLY_FILLED    // ls=12
FILLED              // ls=13
CANCELED            // ls=14
REJECTED            // ls=15
UNKNOWN             // ls=16
RECOVERING          // ls=17
ERROR               // ls=18
Order.markExecuting() (Order.java:169)
Исходное: PENDING_EXECUTION → Целевое: EXECUTING
Правило: validateAndPassThrough(PENDING_EXECUTION, EXECUTING) → ALLOWED
STATE_GRAPH[PENDING_EXECUTION] содержит EXECUTING ✅

Order.fill() (Order.java:174)
Исходное: EXECUTING → Целевое: FILLED
Правило: validateAndPassThrough(EXECUTING, FILLED) → ALLOWED
STATE_GRAPH[EXECUTING] содержит FILLED ✅

Исходное: PENDING_EXECUTION → Целевое: FILLED
Правило: validateAndPassThrough(PENDING_EXECUTION, FILLED) → ЗАПРЕЩЕНО
STATE_GRAPH[PENDING_EXECUTION] НЕ содержит FILLED ❌

Таблица переходов
FROM	TO	ALLOWED?	Ключ графа
NEW	PENDING_EXECUTION	✅	NEW→{PENDING_EXECUTION, REJECTED}
NEW	REJECTED	✅
PENDING_EXECUTION	EXECUTING	✅	PENDING_EXECUTION→{EXECUTING, CANCELED, REJECTED}
PENDING_EXECUTION	CANCELED	✅
PENDING_EXECUTION	REJECTED	✅
PENDING_EXECUTION	FILLED	❌	НЕТ В ГРАФЕ
EXECUTING	FILLED	✅	EXECUTING→{...,FILLED,...}
EXECUTING	PARTIALLY_FILLED	✅
EXECUTING	REJECTED	✅
EXECUTING	UNKNOWN	✅
SECTION 2 — EXECUTION FLOW
Signal
↓
Order.createPendingExecution()  → status = PENDING_EXECUTION
↓
ORDER_CREATED outbox event published
↓
OrderExecutionHandler.consume()  ← @Override, НЕТ @Transactional
│
├─ PHASE 1: claimOrder()        ← @Transactional(REQUIRES_NEW)  → TX1
│   ├─ executionClaimPort.existsByExecutionId()
│   ├─ executionClaimPort.claimExecution()
│   └─ orderRepository.claimForExecution(orderId, context)  ← @Transactional(REQUIRES_NEW)
│       └─ claimForExecutionInCurrentTransaction()  ← @Transactional(REQUIRED)
│           ├─ orderMapper.toDomain(entity)        → Order { status=PENDING_EXECUTION }
│           ├─ order.assignExecutionOwner(execId)  → executionId = X
│           ├─ orderMapper.updateEntity(order, entity)
│           └─ orderRepository.saveAndFlush(entity)  → COMMIT TX1
│
├─ return Order { status=PENDING_EXECUTION, executionId=X }
│
├─ PHASE 2: executionPort.placeOrder(order)  ← ВНЕ ТРАНЗАКЦИИ
│
└─ PHASE 3: commitExecution(event, ctx, order, result, lockKey)  ← @Transactional(REQUIRES_NEW) → TX2
├─ ExecutionOwnershipValidator.validateExecutionOwnership(order, ...)
├─ order.fill(ctx, exchangeOrderId, qty, price)
│   └─ validateAndPassThrough(PENDING_EXECUTION, FILLED) → 💥 THROW
└─ orderRepository.save(order)
Шаг	Класс	Метод	TX	Состояние до	Состояние после
Создание	Order	createPendingExecution	—	—	PENDING_EXECUTION
Outbox	OutboxService	publishEvent	TX	—	—
Consume	OrderExecutionHandler	consume()	НЕТ TX	—	—
Claim	OrderExecutionHandler	claimOrder()	TX1 (REQUIRES_NEW)	PENDING_EXECUTION (DB)	PENDING_EXECUTION (in-memory)
IO	ExecutionPort	placeOrder()	НЕТ TX	—	—
Commit	OrderExecutionHandler	commitExecution()	TX2 (REQUIRES_NEW)	PENDING_EXECUTION	💥 CRASH
SECTION 3 — markExecuting ANALYSIS
ВСЕ вызовы order.markExecuting()
src/main/java/com/tradingbot/domain/model/Order.java:169  ← ТОЛЬКО ОПРЕДЕЛЕНИЕ
ВЫЗОВОВ — НОЛЬ.

Grep по всей кодовой базе:

markExecuting → 1 результат: только определение метода в Order.java:169
Ответы:
Может ли markExecuting() выполниться без сохранения?
Метод нигде не вызывается. Вопрос нерелевантен.

Может ли markExecuting() откатиться?
Метод нигде не вызывается. Вопрос нерелевантен.

Может ли commitExecution() увидеть старое состояние?
CommitExecution ВСЕГДА видит PENDING_EXECUTION, потому что markExecuting() никогда не вызывается ни в claimOrder(), ни в consume().

SECTION 4 — TRANSACTION VISIBILITY
Аннотации:
Метод	@Transactional	Propagation
consume() (l.57)	НЕТ	—
claimOrder() (l.117)	@Transactional	REQUIRES_NEW
commitExecution() (l.156)	@Transactional	REQUIRES_NEW
Схема:
consume()                           ← НЕТ транзакции
│
├─ TX1 (REQUIRES_NEW)
│   └─ claimOrder()
│       ├─ executionClaimPort.claimExecution()
│       └─ orderRepository.claimForExecution(orderId, ctx)
│           ├─ order.assignExecutionOwner(execId)
│           ├─ updateEntity()
│           └─ saveAndFlush()  ← COMMIT TX1
│
│ [ВНЕ ТРАНЗАКЦИЙ]
│
├─ executionPort.placeOrder(order)  ← объект order в памяти,
│                                     статус = PENDING_EXECUTION
│
└─ TX2 (REQUIRES_NEW)
└─ commitExecution(event, ctx, order, result, lockKey)
└─ order.fill()  ← order тот же объект из памяти
статус = PENDING_EXECUTION
Ответы:
commitExecution читает Order из БД или использует тот же объект?
Тот же объект в памяти. Параметр Order order передаётся из consume() л.70: Order order = orderOpt.get();. Никакой перезагрузки из БД перед commitExecution() нет.

Может ли TX2 стартовать до commit TX1?
Нет. TX1 коммитится в момент выхода из claimOrder() (REQUIRES_NEW). TX2 стартует при входе в commitExecution() (REQUIRES_NEW). Они выполняются последовательно.

Может ли TX2 видеть PENDING_EXECUTION после markExecuting?
Да. Но проблема не в видимости — проблема в том, что markExecuting вообще не вызывается.

SECTION 5 — RELOAD ANALYSIS
Повторная загрузка Order из БД между markExecuting() и fill():

markExecuting() — НЕ ВЫЗЫВАЕТСЯ
↓
[ПУСТО]
↓
fill() в commitExecution()
Никаких вызовов orderRepository.findById(), entityManager.find() между claimOrder() и commitExecution() нет.

Единственный findById в commitExecution-связанном коде — внутри orderRepository.save() (l.96-97 OrderRepositoryAdapter):

OrderEntity entity = orderRepository.findByIdForUpdate(order.getId())
.orElseThrow(...);
orderMapper.updateEntity(order, entity);
Это загружает entity из БД, но использует данные из in-memory order через updateEntity() для обновления. updateEntity() не обновляет status из entity — только копирует поля из domain Order в entity. Так что даже здесь статус берётся из in-memory объекта, который всё ещё PENDING_EXECUTION.

SECTION 6 — FINAL ROOT CAUSE
1. Почему fill() получает Order в состоянии PENDING_EXECUTION?
   Потому что markExecuting() нигде не вызывается в execution flow. Order создаётся в PENDING_EXECUTION, проходит через claimForExecution() (где ему присваивается executionId, но статус не меняется), и попадает в commitExecution() → fill() всё ещё с PENDING_EXECUTION.

2. Где именно теряется переход в EXECUTING?
   В claimOrder() (OrderExecutionHandler.java:117–154). После успешного orderRepository.claimForExecution() (строка 147) должен быть вызов order.markExecuting(context), но его нет:

// Строки 147-153 OrderExecutionHandler.java:
Optional<Order> orderOpt = orderRepository.claimForExecution(orderId, context);
if (orderOpt.isEmpty()) {
handleAlreadyProcessed(event);
return Optional.empty();
}

return orderOpt;  // ← Order возвращается со статусом PENDING_EXECUTION
//                  markExecuting() НЕ ВЫЗВАН
Контраст: внутри claimForExecutionInCurrentTransaction() (OrderRepositoryAdapter.java:55) вызывается order.assignExecutionOwner(incomingExecutionId) — присваивается executionId, но не статус EXECUTING.

3. Причина:
   A) Отсутствующий markExecuting ✅

Вариант	Вердикт
A) отсутствующий markExecuting	✅ ДА — корневая причина
B) отсутствие save	❌ save есть (saveAndFlush)
C) rollback	❌ rollback-а нет
D) transaction visibility problem	❌ транзакции последовательные
E) повторная загрузка старой версии	❌ reload не происходит
F) другое	❌
Точный участок, приводящий к runtime ошибке:
OrderExecutionHandler.java:166 — вызов order.fill(...) в commitExecution():

// commitExecution(), строка 165-167
if (result.getStatus() == ExecutionResult.Status.SUCCESS) {
order.fill(              // ← order.status == PENDING_EXECUTION
context,
result.getExchangeOrderId(),
...
);
}
↓ вызывает

Order.java:174-175:

public void fill(ExecutionContext context, String exchangeOrderId, ...) {
OrderStateTransitionPolicy.validateAndPassThrough(context, this.status, OrderStatus.FILLED);
// this.status == PENDING_EXECUTION → 💥 IllegalStateException
↓ вызывает

OrderStateTransitionPolicy.java:18-22:

if (!canTransition(current, target)) {  // canTransition(PENDING_EXECUTION, FILLED) → false
throw new IllegalStateException(String.format(
"IDENTITY-STRICT-VIOLATION: Transition from %s to %s is forbidden...",
current, target, ...));
}
Цепочка:

claimOrder() → (markExecuting пропущен) → commitExecution() → fill() → 💥
Исправление: добавить order.markExecuting(context) после orderRepository.claimForExecution() в claimOrder() (OrderExecutionHandler.java, между строками 147 и 153).