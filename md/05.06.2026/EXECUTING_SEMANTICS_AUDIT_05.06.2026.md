EXECUTING SEMANTICS AUDIT
1. Все комментарии и Javadoc для PENDING_EXECUTION и EXECUTING
   OrderStatus enum (OrderStatus.java)
   Строка	Статус	Комментарий
   9	PENDING_EXECUTION	// Ожидает асинхронного исполнения
   10	EXECUTING	// В процессе исполнения (атомарный захват)
   Order.markExecuting() (Order.java:169)
   // НЕТ JavaDoc, только:
   public void markExecuting(ExecutionContext context) {
   OrderStateTransitionPolicy.validateAndPassThrough(context, this.status, OrderStatus.EXECUTING);
   this.status = OrderStatus.EXECUTING;
   }
   JavaDoc отсутствует. Метод не документирован.

OrderStateTransitionPolicy (OrderStateTransitionPolicy.java)
Строка	Комментарий
34	// PENDING_EXECUTION -> EXECUTING (захват), CANCELED (отмена до отправки), REJECTED (reconciliation)
35	// EXECUTING -> EXECUTING (идемпотентность), SENT_TO_EXCHANGE, FILLED, PARTIALLY_FILLED, REJECTED, UNKNOWN, CANCELED
OrderWatchdogService (OrderWatchdogService.java:37)
// Ищем ордера, которые зависли в EXECUTING (уже ушли на биржу, но не подтверждены)
ReconciliationService (ReconciliationService.java:220)
// Обработка stuck PENDING_EXECUTION или RECOVERING без ответа от биржи
2. Все места использования OrderStatus.EXECUTING
#	Файл	Строка	Контекст
1	OrderStatus.java	10	Определение enum-значения
2	OrderStateTransitionPolicy.java	35	PENDING_EXECUTION → {EXECUTING, CANCELED, REJECTED}
3	OrderStateTransitionPolicy.java	36-37	EXECUTING — ключ графа исходящих переходов
4	OrderStateTransitionPolicy.java	66	UNKNOWN → {..., EXECUTING, RECOVERING} (recovery retry)
5	OrderStateTransitionPolicy.java	96	requestTransition() — guard: переход в UNKNOWN только из EXECUTING
6	OrderStateTransitionPolicy.java	133	isStale() — stale только если status==EXECUTING
7	OrderStateTransitionPolicy.java	151	getReconcilableStatuses() — EXECUTING в списке
8	Order.java	170-171	markExecuting() — определение, переход в EXECUTING
9	OrderRepositoryAdapter.java	79,82	claimForReconciliation() — guard: если EXECUTING и не stale → отказ
10	OrderWatchdogService.java	37,39,43	checkStuckOrders() — ищет ордера в EXECUTING
11	ExecutionLockService.java	20-21	tryEnterExecuting() — lock-сервис (не состояние Order)
12	ExecutionStateMapper.java	16	Маппинг для логов: EXECUTING → "EXECUTING"
3. Код, который ожидает PENDING_EXECUTION или EXECUTING на конкретных этапах
   3a. Ожидает PENDING_EXECUTION после claim
   Да, явно:

OrderRepositoryAdapter.java:43 — claimForExecutionInCurrentTransaction():

if (entity.getStatus() != OrderStatus.PENDING_EXECUTION && !isStale) {
return Optional.empty();  // ← отказ, если не PENDING_EXECUTION и не stale
}
Импакт: если добавить markExecuting() в claimOrder() и потом сохранить (saveAndFlush), то при retry из Outbox (withTransportRetry() сохраняет executionId) проверка executionClaimPort.existsByExecutionId() вернёт true и вернёмся раньше (строка 135-137 claimOrder()).

Но если executionId по какой-то причине прошёл — guard entity.getStatus() != PENDING_EXECUTION && !isStale заблокирует повторный claim (статус уже EXECUTING, не stale).

3b. Ожидает EXECUTING до placeOrder
Нет. executionPort.placeOrder(order) вызывается в consume() (строка 90) без какой-либо проверки статуса. Он принимает объект Order как есть.

3c. Ожидает EXECUTING для перехода в UNKNOWN
Да: OrderStateTransitionPolicy.java:96:

if (target == OrderStatus.UNKNOWN && current != OrderStatus.EXECUTING) {
throw new IllegalStateException("Transition to UNKNOWN is only allowed from EXECUTING. Current: " + current);
}
Но этот guard находится в методе requestTransition(), который нигде не вызывается (grep показал 0 вызовов). Реальный guard — validateAndPassThrough() + граф состояний, где EXECUTING → UNKNOWN разрешён. С PENDING_EXECUTION → UNKNOWN запрещён.

Импакт: в commitExecution() (строка 174-175) при TIMEOUT вызывается order.markAsUnknown(context). Если статус всё ещё PENDING_EXECUTION — тоже будет исключение, как сейчас с FILLED. PENDING_EXECUTION → UNKNOWN нет в графе.

4. Recovery/Reconciliation логика, опирающаяся на EXECUTING
   4a. OrderWatchdogService
   // OrderWatchdogService.java:37-43
   List<OrderEntity> stuckOrders = orderRepository.findStuckOrders(
   OrderStatus.EXECUTING,
   threshold
   );
   Зависимость: Watchdog ищет только ордера в EXECUTING. Если ордер остаётся в PENDING_EXECUTION, Watchdog его не обнаружит.

Импакт при добавлении markExecuting: Watchdog начнёт находить ордера, у которых placeOrder() выполнился, но commitExecution() упал/не вызван. Это правильное поведение — сейчас Watchdog не видит такие ордера (они PENDING_EXECUTION), что является пробелом в recovery.

4b. ReconciliationService
// ReconciliationService.java:177-179
if (orderOpt.isEmpty()) {
log.debug("[RECON-SKIP] Order {} is currently executing or terminal.", targetOrder.getId());
return;
}
Guard в claimForReconciliation() (OrderRepositoryAdapter.java:79-82):

boolean isExecuting = entity.getStatus() == OrderStatus.EXECUTING;
boolean isStale = transitionValidator.isStale(entity.getStatus(), entity.getExecutionStartedAt());
if (isExecuting && !isStale) {
return Optional.empty();  // ← активный EXECUTING → reconciliation не трогает
}
Зависимость: EXECUTING + не-stale = ордер в активной обработке → reconciliation пропускает.

Импакт при добавлении markExecuting: Ордер, который только что прошёл CLAIM, получил EXECUTING, но placeOrder() ещё не вызван — reconciliation его не тронет (EXECUTING + свежий executionStartedAt). Это правильно. Но если placeOrder завис на 2+ минуты — isStale() вернёт true, и reconciliation начнёт восстановление.

4c. isStale()
// OrderStateTransitionPolicy.java:133
public static boolean isStale(OrderStatus status, Instant startedAt) {
if (status == OrderStatus.EXECUTING && startedAt != null) {
return startedAt.isBefore(Instant.now().minus(Duration.ofSeconds(30)));
}
return false;
}
Зависимость: isStale() работает только для EXECUTING. PENDING_EXECUTION никогда не считается stale.

Импакт при добавлении markExecuting: Stale-детекция начнёт работать. Ордера, зависшие в EXECUTING > 30 секунд, будут определяться как stale, что позволит claimForExecution и reconciliation их перехватывать.

4d. getReconcilableStatuses()
// OrderStateTransitionPolicy.java:151
return Set.of(PENDING_EXECUTION, EXECUTING, SENT_TO_EXCHANGE,
PARTIALLY_FILLED, UNKNOWN, RECOVERING);
Оба статуса уже в списке. Без изменений.

4e. ExecutionLockService
// ExecutionLockService.java:20-21
public boolean tryEnterExecuting(String idempotencyKey) {
return repository.updateToExecuting(idempotencyKey) > 0;
}
Это отдельная таблица execution_lock с состояниями CLAIMED → EXECUTING → EXECUTED. Не связано с OrderStatus.EXECUTING. Без изменений.

5. Если добавить markExecuting сразу после claim — какие инварианты изменятся
   Инварианты, которые ИЗМЕНЯТСЯ
#	Инвариант	Текущее поведение	Новое поведение
1	Статус после claim	PENDING_EXECUTION	EXECUTING
2	claimForExecution() double-claim	Блокирует: status != PENDING_EXECUTION && !isStale	Тоже блокирует: EXECUTING && !stale → возврат empty. Но +30с stale-окно для recovery
3	Watchdog видимость	Не видит ордера после claim (они PENDING_EXECUTION)	Начинает видеть ордера в EXECUTING, застрявшие >2 мин
4	Reconciliation skip logic	EXECUTING + не-stale пропускается	Теперь активные ордера защищены от reconciliation (EXECUTING + свежий timestamp)
5	Timeout → UNKNOWN переход	PENDING_EXECUTION → UNKNOWN = 💥 (сейчас не доходит, т.к. fill падает раньше)	EXECUTING → UNKNOWN = ✅ разрешён в графе
6	fill() допустимость	💥 PENDING_EXECUTION → FILLED запрещён	✅ EXECUTING → FILLED разрешён
7	Stale-детекция	PENDING_EXECUTION никогда не stale	EXECUTING становится stale через 30с
Инварианты, которые НЕ ИЗМЕНЯТСЯ
Инвариант	Почему
isProcessed()	Оба статуса ≠ NEW, ≠ PENDING_EXECUTION — для EXECUTING тоже true (но isProcessed проверяет != PENDING_EXECUTION)
isActive()	Оба не-терминальные и ≠ NEW
isReconcilable()	Оба в списке reconcilable
isReadyForExecution()	Проверяет == PENDING_EXECUTION — EXECUTING вернёт false. Это значит: watchdog/recon НЕ будет пытаться заново исполнить через claimForExecution (выйдет через guard)
ExecutionLockService	Отдельная таблица, не зависит от OrderStatus
requestTransition() guard UNKNOWN	Не вызывается нигде
Ключевой вывод
Добавление markExecuting() после claim не ломает ни один guard. Все проверки в claimForExecutionInCurrentTransaction, claimForReconciliation, isStale, reconcilableStatuses уже явно учитывают EXECUTING. Единственное изменение — система начинает работать так, как спроектирована: stale-детекция, watchdog-видимость и защита от reconciliation для активных ордеров вступают в силу. Сейчас они dead code, потому что ни один ордер никогда не переходит в EXECUTING.   что скажешь?