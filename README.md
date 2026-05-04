🩺 Рекомендации (хирургический рефакторинг PRE-INFRA)
OrderExecutionHandler → Claim-Execute-Commit + Layer Separation
Claim: атомарный захват через Pessimistic Lock на OrderRepositoryAdapter (инфраструктура).
Execute: чисто вызов внешней биржи, вне транзакции.
Commit: фиксация статуса, публикация Outbox после commit через TransactionSynchronizationManager.
Важно: OrderExecutionHandler больше не знает о сущностях DB напрямую, работает только с доменной моделью Order.


1️⃣ Вводим чистую доменную модель и адаптеры

Что делаем:

OrderExecutionHandler больше не работает с OrderEntity или OrderMapper.
Создаём OrderRepositoryPort (интерфейс в Domain Layer), который скрывает детали JPA и предоставляет:
Optional<Order> claimOrder(orderId)
void save(Order order) или Order commit(Order order, ExecutionResult result)
Адаптер OrderRepositoryAdapter реализует OrderRepositoryPort и внутри использует OrderEntity + JPA.

Цель: отделить доменную модель от инфраструктуры.

2️⃣ Перенос логики Claim в домен

Что делаем:

В Order создаём метод:
public boolean claim(ExecutionId executionId) {
if (state != OrderState.PENDING) return false;
this.state = OrderState.CLAIMED;
this.executionId = executionId;
return true;
}
OrderExecutionHandler.claimOrder(orderId) теперь:
Через OrderRepositoryPort.claimOrder(id) получает Order
Вызывает order.claim(executionId)
Сохраняет через port.save(order)

Цель: атомарный захват через порт, логика статуса внутри домена, никаких сеттеров Entity в Handler.

3️⃣ Execute — чистый вызов внешней биржи

Что делаем:

executeOrder(Order order) не меняет Order и не работает с DB.
Логика retry и блокировок (ExecutionLockService) переносится в инфраструктурный ExecutionPort.
Handler получает ExecutionResult и дальше идёт в Commit.
ExecutionResult result = executionPort.execute(order);

Цель: никакой side-effect внутри execute, Handler знает только доменную модель + результат.

4️⃣ Commit через домен + TransactionSynchronizationManager

Что делаем:

В Order добавляем метод commit:
public void commit(ExecutionResult result) {
this.state = result.isFilled() ? OrderState.FILLED : OrderState.REJECTED;
this.executionResult = result;
}
В OrderExecutionHandler.commitOrder(order, result):
Вызывает order.commit(result)
Сохраняет через port.save(order)
Регистрирует Outbox через TransactionSynchronizationManager.afterCommit(() -> outboxPort.publish(order))
Убираем все синхронные вызовы к RiskEngine из commit (если нужны, делать через Event / Outbox / асинхронный listener)

Цель: фиксация состояния атомарно, публикация Outbox только после commit, идемпотентно.

5️⃣ Убираем прямые зависимости на JPA / Spring / Entity

Что делаем:

OrderExecutionHandler больше не знает о OrderEntity, Repository, Mapper.
Все инфраструктурные операции идут через порты:
OrderRepositoryPort (DB)
ExecutionPort (биржа)
OutboxPort (публикация событий)
Handler становится чистым Application Service, работает только с Order и ExecutionResult.
6️⃣ Проверка после рефакторинга
Unit-тесты на Claim-Execute-Commit:
Claim атомарный, idempotent, один поток.
Execute чистый, retry-safe, без side-effects.
Commit атомарный + Outbox afterCommit.
CodeQL / Audit снова прогоняем с промтом, который мы составили ранее, чтобы закрыть 100% нарушений.
Убеждаемся, что Layer Separation полностью соблюдена.








RiskEngine → Domain Events + Atomic Reserve
RiskService генерирует события CapitalReserved, инфраструктура пишет в лог.
SELECT FOR UPDATE на балансе/позиции скрыт за RiskRepository.
Убираем все локальные ReentrantLock.





State Machine → Freeze Transitions
Все проверки переходов внутри Order.
TransitionExecutor только сохраняет состояние, не влияет на логику.
Никаких “временных переходов” PENDING → FILLED без RESERVE → SENT.





Outbox → Retry-Safe
Публикация строго после commit, без side-effects внутри транзакций.
Нам нужен механизм idempotency на уровне executionId для защиты от дублирования.