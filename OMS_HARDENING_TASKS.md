# 🛠 OMS Hardening: Detailed Task List (Stage 3.5)

Этот документ содержит пошаговый план реализации архитектуры "OMS как распределенный слой консистентности".

---

## 🧠 ГЛАВНЫЕ ПРАВИЛА (State Authority)
1. **ONLY FSM can change OrderStatus**: Ни один сервис (Listener, Recovery, Sync) не имеет права менять статус напрямую. Только через `OrderStateMachine.getNextStatus()`.
2. **Fencing First**: Любое действие с ордером начинается с захвата `lease` (fencing token).
3. **DB is Source of Truth**: Внешние системы (Binance) — это валидаторы, а не контроллеры.

---

## 📦 Блок 1: Domain & Fencing (Фундамент) — [ВЫПОЛНЕНО]
*Цель: Создать механизмы контроля состояний и владения.*
- [x] **1.1 OrderEvent**: Создан Enum с событиями.
- [x] **1.2 OrderStateMachine**: Реализован валидатор переходов.
- [x] **1.3 OrderRepository**: Добавлен атомарный метод `tryAcquireOrder`.
- [x] **1.4 OrderFencingService**: Создан сервис для управления лизингом.

---

## 🧱 Блок 2: Application Layer (Ingestion) — [В ПРОЦЕССЕ]
*Цель: Превратить OMS в чистый шлюз данных.*
- [x] **2.1 Refactor OrderManagementService**:
    - Удален `processedSignals` (дедупликация на уровне БД).
    - Удален прямой вызов `ExecutionEngine`.
    - Внедрена публикация `OrderReadyForExecutionEvent`.
- [ ] **2.2 Event Model**: Убедиться, что `OrderReadyForExecutionEvent` содержит все данные для исполнения без повторного чтения тяжелых объектов (но с проверкой актуальности в БД).

---

## ⚡ Блок 3: Execution Layer (Hardening)
*Цель: Гарантировать безопасное исполнение через строгий Order of Operations.*

**Порядок операций в Listener:**
1. `tryAcquire(orderId)` (Fencing) — если не удалось, выходим.
2. `FSM.getNextStatus(CURRENT, EXECUTION_STARTED)` -> `status = EXECUTING`.
3. `executionEngine.execute(order)` (External call).
4. `FSM.getNextStatus(EXECUTING, SUCCESS/FAIL)` -> `status = FILLED/REJECTED`.
5. `persist()` (Final state).

- [ ] **3.1 OrderExecutionListener**:
    - Перевести на `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`.
    - Реализовать вышеуказанный Order of Operations.
- [ ] **3.2 Error Handling**: Реализовать освобождение lease (set owner = null) при инфраструктурных ошибках, чтобы Recovery мог подхватить ордер.

---

## 🔄 Блок 4: Recovery & Consistency (Reliability)
*Цель: Автоматическое восстановление и синхронизация с биржей.*

- [ ] **4.1 OrderRecoveryService (Detection Only)**:
    - **Запрещено**: Прямое исполнение или смена статуса.
    - **Разрешено**: Поиск ордеров в `PENDING` или `EXECUTING` (с истекшим lease) и перепубликация события `OrderReadyForExecutionEvent`.
- [ ] **4.2 ReconciliationService (Verification Layer)**:
    - Фоновая сверка с Binance по `clientOrderId`.
    - Если в БД `EXECUTING`, а на Binance `FILLED` -> инициировать `EXTERNAL_SYNC` через FSM.
    - Если в БД `EXECUTING`, а на Binance `NOT FOUND` (и прошло много времени) -> инициировать `EXECUTION_FAILED`.

---

## 🧪 Блок 5: Verification (Testing)
*Цель: Доказать надежность системы.*
- [ ] **5.1 Race Condition Test**: 10 потоков пытаются обработать один сигнал. Ожидаем: 1 ордер в БД, 1 исполнение на бирже.
- [ ] **5.2 Recovery Test**: Убийство потока сразу после `executionEngine.execute()`. Ожидаем: Recovery находит ордер, Reconciliation подтверждает статус на бирже, БД обновляется до `FILLED`.
- [ ] **5.3 Idempotency Test**: Повторная отправка того же `clientOrderId` после того, как ордер уже в терминальном состоянии.
