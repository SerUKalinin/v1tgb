# PHASE 0: ATOMIC CORE & FINANCIAL CONSISTENCY DESIGN

## 1. CURRENT STATE ANALYSIS

На текущий момент система имеет работающий сквозной поток, но он не пригоден для реальной торговли (Real-Money Trading) из-за следующих архитектурных дефектов:

*   **Execution inside Transaction**: В `OrderManagementService` вызов `executionEngine.executeOrder()` происходит внутри метода, помеченного `@Transactional`. Это блокирует соединение с БД на время сетевого запроса к Binance (IO-bound), что ведет к исчерпанию пула соединений и риску "зависших" транзакций.
*   **Partial State Risk**: Если сетевой запрос к бирже прошел успешно, но транзакция в БД откатилась (например, из-за ошибки записи Outbox или таймаута), система "забудет" об исполненном ордере. Это ведет к потере контроля над позицией и деньгами.
*   **Risk Bypass**: Проверка рисков в `DefaultRiskManager` носит консультативный характер. Нет жесткой связи на уровне БД между резервированием капитала и созданием ордера.
*   **Outbox Inconsistency**: Сущность `OutboxEventEntity` существует, но фактически не используется для гарантии доставки. События публикуются через `ApplicationEventPublisher`, который по умолчанию работает синхронно и не гарантирует персистентность при падении JVM.

## 2. CRITICAL PROBLEMS (RANKED)

### BLOCKER: Financial Loss Risk (Severity: 1)
*   **Problem**: Сетевой вызов к бирже внутри транзакции БД.
*   **Impact**: При откате транзакции после успешного исполнения на бирже, система теряет запись об ордере. Результат: "невидимые" открытые позиции, неконтролируемые убытки.

### CRITICAL: Double Execution / Ghost Orders (Severity: 2)
*   **Problem**: Отсутствие идемпотентности на уровне Execution Layer.
*   **Impact**: При ретраях или сбоях сети один и тот же сигнал может привести к созданию нескольких ордеров на бирже.

### MAJOR: Risk Engine Inconsistency (Severity: 3)
*   **Problem**: Отсутствие модели резервирования (Reservation).
*   **Impact**: Риск-движок может одобрить ордер, но к моменту его реального исполнения баланс может измениться другими процессами.

## 3. TARGET ARCHITECTURE (FINAL DESIGN)

### Transaction Boundary: "The Intent Phase"
Транзакция должна включать только локальные изменения в БД:
1.  **RiskEngine**: Проверка лимитов и создание **Reservation** (блокировка части виртуального баланса).
2.  **Order**: Создание записи в статусе `PENDING_EXECUTION`.
3.  **Outbox**: Запись события `ORDER_CREATED` в ту же транзакцию.

### Async Execution Layer: "The Action Phase"
Отдельный процесс (Outbox Processor) читает события и инициирует сетевые вызовы:
1.  Чтение `ORDER_CREATED`.
2.  Вызов `ExecutionEngine` (Binance API) с использованием `clientOrderId` для идемпотентности.
3.  Получение ответа и запись результата через новую транзакцию (статус `FILLED` / `REJECTED`).

## 4. STEP-BY-STEP REFACTOR PLAN

### Step 1: Atomic Order Intent
Создание `OrderApplicationService`, который инкапсулирует создание ордера и Outbox-события в одной `@Transactional` границе.

### Step 2: Reservation Model
Модификация `RiskEngine`. Вместо простого `approve()`, он должен возвращать `ReservationId`, который сохраняется в `OrderEntity`. Баланс в риск-движке уменьшается в момент резервирования.

### Step 3: Execution Isolation
Удаление вызовов `ExecutionEngine` из `OrderManagementService`. Перенос логики в асинхронный слушатель или Outbox Processor.

### Step 4: Reliable Outbox Processor
Реализация планировщика, который гарантированно доставляет события из таблицы `outbox_events` в `ExecutionEngine`.

### Step 5: Idempotency & Recovery
Добавление логики восстановления: при перезапуске система должна найти все `PENDING_EXECUTION` ордера и проверить их статус на бирже по `clientOrderId`.

## 5. CODE STRUCTURE PROPOSAL

```java
// 1. Атомарное создание намерения
@Service
public class OrderApplicationService {
    @Transactional
    public void placeOrder(Signal signal) {
        Reservation res = riskEngine.reserve(signal); // Списывает баланс в RiskState
        Order order = orderRepo.save(Order.builder()
            .status(PENDING_EXECUTION)
            .reservationId(res.getId())
            .build());
        outbox.Entry(new OrderCreatedEvent(order.getId()));
    }
}

// 2. Изолированное исполнение
@Service
public class OutboxProcessor {
    @Scheduled(fixedDelay = 100)
    public void process() {
        events = outbox.findUnprocessed();
        for (event : events) {
            executionEngine.execute(event.getOrderId());
            outbox.markProcessed(event);
        }
    }
}
```

## 6. TRANSACTION RULES (HARD CONSTRAINTS)
1.  **Никаких сетевых вызовов** (HTTP, gRPC, и т.д.) внутри методов с `@Transactional`.
2.  **Никакого исполнения** внутри Domain Services. Только изменение состояния.
3.  **Ордер не может существовать** без активной резервации в RiskEngine.
4.  **Outbox-событие** должно быть создано в той же транзакции, что и `OrderEntity`.

## 7. FINAL GOAL STATE (GOLDEN FLOW)

1.  **Signal** поступает в систему.
2.  **RiskEngine** проверяет баланс и создает **Reservation** (БД).
3.  **Order** сохраняется со статусом `PENDING` (БД).
4.  **OutboxEvent** сохраняется (БД).
5.  **COMMIT TRANSACTION**.
6.  **OutboxProcessor** подхватывает событие.
7.  **ExecutionEngine** отправляет запрос в Binance (Network).
8.  **Binance** подтверждает исполнение.
9.  **Order** обновляется до `FILLED`, **Reservation** подтверждается или закрывается (БД).
