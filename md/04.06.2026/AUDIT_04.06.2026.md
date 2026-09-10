SYSTEM ARCHITECTURE AUDIT REPORT
d:\v1 TGBOT — Distributed Trading/Execution System

1. CRITICAL ISSUES (SYSTEM BREAKING)
   CRIT-1: Double Write to Outbox — TradeService.onOrderFilled vs TradingPipeline.onOrderFilled
   Описание: Существуют два дублирующих класса с идентичной ответственностью: TradeService.onOrderFilled() (L37) и TradingPipeline.onOrderFilled() (L46). Оба:

Создают TradeEntity
Пишут в Outbox ORDER_FILLED + TRADE_CREATED
Нотифицируют RiskEngine.TradeExecuted
Вызывают equityService.onTradeCreated()
При этом TradeService использует OutboxService.publishEvent(), а TradingPipeline — собственную функцию saveOutbox() напрямую в репозиторий. Оба метода помечены @Transactional.

Affected: TradingPipeline.java:46, TradeService.java:37 Impact: При одновременной активации обоих путей — двойное создание трейдов, двойное исполнение, двойная нотификация RiskEngine, двойное обновление Equity. Коррупция данных.

CRIT-2: Position Double Update — Outbox + Синхронный вызов
Описание: TradeService.onOrderFilled() на L114 делает синхронный вызов equityService.onTradeCreated(tradeCreatedEvent). Одновременно в Outbox публикуется TRADE_CREATED, который затем обрабатывается PositionProjectionHandler → вызывает positionService.updatePosition().

Таким образом, при каждой сделке позиция обновляется дважды — один раз синхронно (через EquityService, который в свою очередь читает PositionService.getPosition() на L48), второй раз — асинхронно через Outbox → PositionProjectionHandler.

Affected: TradeService.java:99-112 (outbox TRADE_CREATED) + TradeService.java:114 (синхронный equityService) Impact: Двойное применение сделки к позиции, расхождение PnL, гонка между синхронным и асинхронным путём.

CRIT-3: OrderExecutionHandler.commitExecution — NO @Transactional Propagation Specified
Описание: commitExecution() (L156) объявлен с @Transactional без REQUIRES_NEW, вызывается из consume() (L99), который выполняется внутри OutboxProcessor.processSingleEvent() — уже в транзакции REQUIRES_NEW. Это означает, что commitExecution выполняется в той же транзакции, что и processSingleEvent, и НЕ в отдельной TX2 как задумано архитектурой.

Если outboxService.publishEvent() внутри commitExecution завершится с ошибкой, то rollback затронет и orderRepository.save(order), и lockService.markExecuted(). Внешний IO к бирже уже выполнен без транзакции (Phase 2) — ордер на бирже размещён, но локальное состояние откатится. Inconsistency: ордер исполнен на бирже, локально — нет.

Контракт EXECUTION_ENGINE_CONTRACT.md‎
утверждает: PHASE 3 (COMMIT) должен быть в собственной транзакции. Фактически — НЕТ.

Affected: OrderExecutionHandler.java:156-195, OutboxProcessor.java:128-159 Impact: Откат состояния после успешного исполнения на бирже.

CRIT-4: RiskState — Нет атомарного read-modify-write
Описание: RiskService.evaluateSignal() (L52):

RiskState state = riskStatePort.get(); — читает состояние (L58)
Выполняет несколько проверок и вычислений
riskStatePort.markEventProcessed(eventId, newState, event); — сохраняет (L112-116)
Между шагами 1 и 3 нет распределённой или даже пессимистичной блокировки. RiskStateEntity — синглтон с SELECT ... FOR UPDATE в loadOrInit(), но get() оборачивает это в @Transactional. Проблема в том, что два параллельных вызова evaluateSignal() будут читать одно и то же состояние, резервировать капитал, и оба пройдут проверку RiskPolicy.canReserve() до того как первый запишет результат.

RiskStateReducer.handleCapitalReserved() имеет защиту от дублирования по orderId (L132-135), но при разных orderId и одновременном вызове оба проходят.

Affected: RiskService.java:52-135, RiskStateRepositoryAdapter.java:83-101 Impact: Превышение доступного капитала, двойное резервирование при конкурентных сигналах.

CRIT-5: syncBalance — Прямая перезапись без идемпотентности
Описание: RiskService.syncBalance() (L237) и emergencyStop() (L213) делают riskStatePort.save() напрямую, в обход RiskStateReducer и без проверки processedEventIds. Это ломает событийную модель RiskEngine:

Синхронизация баланса не проходит через reducer → не проверяются инварианты
Нет идемпотентности — при повторном вызове состояние перезапишется
emergencyStop и resumeTrading устанавливают halted в обход событийной модели
Affected: RiskService.java:213-230, 237-243 Impact: Расхождение event-sourced состояния, потеря аудита.

2. HIGH RISK ISSUES
   HIGH-1: Race condition — PositionService.updatePosition() с локальным ReentrantLock
   Описание: PositionService.updatePosition() (L65-96) использует PartitionLockManager — 64-полосный локальный ReentrantLock на основе хеша ключа. Это означает:

Разные инстансы приложения не имеют общей блокировки (нет distributed lock)
При перезапуске блокировка теряется
Хеш-коллизии могут привести к ненужной блокировке несвязанных позиций
Lock захватывается внутри @Transactional — после начала транзакции
Affected: PositionService.java:66-96, PartitionLockManager.java:7-20 Impact: Race conditions при multi-instance деплое, возможен пропуск обновления при коллизиях.

HIGH-2: Idempotency gap — PositionService использует два независимых механизма
Описание: PositionService.updatePosition() проверяет идемпотентность через IdempotencyService.isAlreadyProcessed(event.getTradeId()) — это проверка по глобальной таблице processed_events. Но IdempotencyService.markAsProcessed() вызывается после repository.save(entity) (L91), и при этом markAsProcessed использует Propagation.MANDATORY + saveAndFlush.

Если saveAndFlush на L91 успешен, а markAsProcessed на L92 падает — при повторе isAlreadyProcessed вернёт false, и сделка будет применена повторно.

Affected: PositionService.java:73, 91-92, IdempotencyService.java:18-38 Impact: Двойное применение сделки к позиции при сбое идемпотентности.

HIGH-3: Outbox процессор — обработка без учёта порядка в рамках aggregate
Описание: OutboxProcessor.processSingleEvent() обрабатывает события по одному, но processOutbox() сначала группирует их по aggregateId (L72-77), затем для каждой группы проверяет "gap detection" (L101). Однако processSingleEvent() выполняется в REQUIRES_NEW, что означает, что каждое событие в отдельной транзакции. Если событие 1 падает и уходит в retry, событие 2 может быть успешно обработано раньше — нарушение порядка.

Affected: OutboxProcessor.java:107-110, 128-159 Impact: Нарушение causal ordering внутри агрегата.

HIGH-4: OrderExecutionHandler — idempotency key conflict
Описание: claimOrder() использует executionClaimPort.existsByExecutionId(executionId) для проверки идемпотентности. Но executionId генерируется как IdentityFactory.deriveExecution(orderId, attempt). При transport retry из Outbox используется тот же executionId (context.withTransportRetry()), но если Outbox ретраит после частичного коммита (например, Phase 2 прошёл, Phase 3 упал), то claim определит, что executionId уже существует — и тихо выйдет. Ордер на бирже исполнен, локально — нет, и retry не происходит.

Affected: OrderExecutionHandler.java:118-138 Impact: Молчаливая потеря исполнения.

HIGH-5: DefaultRiskManager.approveSignal() — игнорирует проверку капитала
Описание: DefaultRiskManager.approveSignal() (L39) вызывает riskEngine.evaluateSignal(), что корректно. Но DefaultRiskManager.check() (L42) и evaluate() (L55) проверяют только halt, не проверяя доступный капитал. Если код вызовет check() вместо evaluateAndReserve(), ордер пройдёт без проверки баланса.

Affected: DefaultRiskManager.java:42-52, 55-67 Impact: Потенциальное превышение капитала при неправильном вызове метода.

3. ARCHITECTURAL VIOLATIONS
   ARCH-1: SSOT Violation — PositionState (record) vs Position (Lombok) vs PositionEntity (JPA)
   Описание: Позиция представлена тремя разными моделями с разными полями:

Модель	Поля
PositionState (domain record)	netQuantity, averagePrice, realizedPnl, stopLoss, takeProfit, status, closeRequestId
Position (domain @Value)	netQuantity, avgEntryPrice, updatedAt — нет realizedPnl, нет status
PositionEntity (JPA)	quantity, entryPrice, realizedPnl, stopLoss, takeProfit, status, lastTradeId, version
Три разных представления одного агрегата с разной семантикой полей:

averagePrice vs avgEntryPrice vs entryPrice — три имени для одного концепта
Position не содержит realizedPnl, PositionState и PositionEntity содержат
PositionEntity.applyTrade() реализует свою логику расчёта средней (только для LONG позиций, без Partial Close), а PositionReducer.reduce() — другую (поддержка Reversal, Partial Close)
Affected: PositionState.java, Position.java, PositionEntity.java, PositionReducer.java, PositionRebuildService.java Impact: Вычисления averagePrice и realizedPnl дают разные результаты в зависимости от того, какой путь используется. Rebuild ≠ Live.

ARCH-2: Domain/Infrastructure mixing — RiskService в domain слое зависит от OutboxService (infrastructure)
Описание: com.tradingbot.domain.risk.RiskService (domain пакет!) на L30 принимает OutboxService (из infrastructure.outbox). Это нарушение DDD: доменный сервис не должен знать об инфраструктурном outbox-е. Более того, RiskService НЕ использует outboxService внутри — поле мёртвое, но зависимость в конструкторе осталась.

Affected: RiskService.java:30, 39 Impact: Циклическая/неправильная зависимость, нарушение архитектурных границ.

ARCH-3: JPA Entity содержит бизнес-логику
Описание: PositionEntity.applyTrade() (L67-92) содержит расчёт средней цены и изменение статуса. Это бизнес-логика, которая должна быть в PositionReducer или PositionService, но не в JPA-сущности. При этом алгоритм отличается от PositionReducer.calculateNewAvgPrice():

PositionEntity: только усреднение при увеличении, закрытие сбрасывает цену в 0, нет partial close logic
PositionReducer: полная логика с Reversal, Partial Close, Moving Average
Affected: PositionEntity.java:67-92 Impact: Бизнес-логика в persistence слое, два несовместимых алгоритма.

ARCH-4: TradingPipeline.onOrderFilled() — встроенная saveOutbox() минуя OutboxService
Описание: TradingPipeline.saveOutbox() (L110-130) создаёт OutboxEventEntity напрямую, не проходя через OutboxService.publishEvent(). При этом:

Нет проверки на дубликат по executionId (в отличие от OutboxService L32-34)
Нет генерации sequence number
Нет проверки schemaVersion
ID генерируется через IdentityFactory.deriveEventId() вместо прямого context.attempt().executionId()
Affected: TradingPipeline.java:110-130, OutboxService.java:25-61 Impact: Два пути создания Outbox-событий с разной логикой идемпотентности.

ARCH-5: Два конкурирующих роутера
Описание: Существуют OutboxEventRouter (в application.event) и OutboxDispatcher (в infrastructure.outbox) — оба делают одно и то же: ищут OutboxConsumer по eventType и вызывают consume(). Используется только OutboxEventRouter (через OutboxProcessor). OutboxDispatcher — мёртвый код.

Affected: OutboxEventRouter.java, OutboxDispatcher.java Impact: Путаница, dead code.

4. EVENT FLOW PROBLEMS
   EVENT-1: ORDER_FILLED → RiskEngine публикуется из Outbox-контекста, а не из доменного
   Описание: В TradeService.onOrderFilled() строка L89-96:

riskEngine.publish(new RiskEvent.TradeExecuted(...))
Этот вызов находится внутри транзакции обработки события, но сам RiskEvent.TradeExecuted отправляется синхронно через RiskService.publish(), который вызывает riskStatePort.markEventProcessed(). Это означает, что RiskEngine обновляется синхронно в той же транзакции, где создаётся Trade. Если Trade закоммитится, но RiskState не закоммитится (или наоборот) — инконсистентность.

Affected: TradeService.java:88-96 Impact: RiskState может не отражать реальные сделки.

EVENT-2: ORDER_EXECUTED дублируется
Описание: OrderExecutionHandler.commitExecution() на L186-191 публикует ORDER_EXECUTED в Outbox. Но OrderExecutedEventHandler.consume() на L69-91 применяет order.fill() повторно (ордер уже заполнен в commitExecution). Это значит, что переход состояния ордера происходит дважды — первый раз в commitExecution, второй раз в OrderExecutedEventHandler.

К счастью, Order.fill() имеет защиту validateAndPassThrough (current == target → return), но это скрытая проблема — логика дублируется.

Affected: OrderExecutionHandler.java:166-177, 186-191, OrderExecutedEventHandler.java:68-74 Impact: Избыточное применение состояния, потенциальные конфликты при расширении логики.

EVENT-3: Цепочка причинности ломается на TRADE_CREATED
Описание: В TradeService.onOrderFilled():

Outbox ORDER_FILLED с executionId из контекста (L47-52)
Outbox TRADE_CREATED с новым executionId = IdentityFactory.deriveEventId(context.attempt().executionId(), "trade-publish") (L77-86)
Синхронный TradeCreatedEvent с тем же executionId (L99-112)
НО в TradeService ID трейда (tradeId) генерируется как IdentityFactory.deriveEventId(context.attempt().executionId(), "trade") (L62), а в TradingPipeline ID трейда не генерируется вообще — TradeEntity создаётся без setId().

Affected: TradeService.java:62, 77-78, TradingPipeline.java:65 Impact: Разные ID трейдов при разных путях, невозможность детерминированного реплея.

EVENT-4: signalId используется как orderId в handleAlreadyProcessed
Описание: OrderExecutionHandler.handleAlreadyProcessed() (L197-203):

orderRepository.findById(event.getSignalId())...
Ищет ордер по signalId, а не по orderId. Это баг: signalId != orderId по контракту системы. Если вызовется — findById вернёт пустой Optional.

Affected: OrderExecutionHandler.java:198 Impact: Молчаливый пропуск обработки уже обработанных ордеров.

5. POSITION SYSTEM AUDIT (DEEP)
   POS-1: Два несогласованных алгоритма расчёта средней цены
   Аспект	PositionReducer (Live)	PositionEntity.applyTrade()	PositionRebuildService
   Открытие	Средняя по total cost	Средняя по total cost	Средняя по total cost
   Добавление	Средняя по total cost	Средняя по total cost	Средняя по total cost
   Частичное закрытие	Цена не меняется	Цена не затрагивается вообще	Пропорциональное списание cost basis
   Разворот	Новая цена входа	Нет логики разворота	Нет логики разворота
   Realized PnL	Рассчитывается	Не рассчитывается	Пропорциональный cost basis
   Ключевое расхождение: PositionRebuildService.calculatePosition() (L73-116) списывает boughtCost пропорционально при SELL, меняя среднюю цену. PositionReducer.calculateNewAvgPrice() при частичном закрытии не меняет averagePrice. Результат Rebuild никогда не совпадёт с Live-состоянием при частичных закрытиях.

Impact: Фундаментальное расхождение позиций после холодного старта / ребилда.

POS-2: PositionService кэш vs БД — расхождение
Описание: PositionService держит Map<String, Position> positions (ConcurrentHashMap) как кэш (L39). При старте кэш загружается из БД (L46-57). Но:

PositionMapper.toDomain() создаёт Position (Lombok @Value) — без realizedPnl и без status
PositionEntity в БД содержит realizedPnl и status
PositionRebuildService сохраняет PositionEntity с realizedPnl
→ Кэш positions не содержит realizedPnl. PnL в кэше теряется.

Affected: PositionService.java:39, 51-52, PositionMapper, Position.java Impact: Невозможность получить realisedPnl из кэша.

POS-3: PositionStatus vs String status
Описание: Домен использует enum PositionStatus (NEW, OPEN, CLOSING, CLOSED). PositionEntity.status — это String (L52-53), не enum. PositionEntity.applyTrade() устанавливает "CLOSED" или "OPEN" (L88-90), но никогда "NEW" или "CLOSING". PositionReducer использует enum. При загрузке из БД статус может быть любым значением.

Affected: PositionEntity.java:52-53, PositionStatus.java, PositionReducer.java Impact: Несоответствие модели статуса, возможность некорректного статуса в БД.

POS-4: PositionEntity.applyTrade() — только LONG
Описание: applyTrade() на L73-75:

if (newQuantity.signum() < 0) {
throw new IllegalStateException("Position quantity cannot be negative");
}
Система не поддерживает SHORT позиции на уровне JPA-сущности. PositionReducer напротив, поддерживает (netQuantity может быть отрицательным при SELL-сигнале). Несоответствие.

Affected: PositionEntity.java:73-75, PositionReducer.java:16-17 Impact: SHORT позиции невозможны через Entity, но возможны через Reducer.

POS-5: Rebuild не использует Reducer
Описание: PositionRebuildService реализует полностью независимую логику расчёта (L73-116), не используя PositionReducer. Это третий источник логики расчёта позиций. При любом изменении алгоритма в Reducer'е, Rebuild останется со старой логикой.

Affected: PositionRebuildService.java:73-116 Impact: Разные результаты при восстановлении.

6. RISK SUBSYSTEM CONSISTENCY
   RISK-1: RiskState помечен как immutable, но НЕ immutable
   Описание: RiskState аннотирован @Data (L16) с комментарием "Immutable state". Но:

@Data генерирует setHalted(true) — и RiskStateReducer.checkAndApplyAutoHalt() вызывает state.setHalted(true) (L74, 83)
toBuilder() создаёт изменяемую копию
processedEventIds — Set<String>, но мутабельный сет вставляется через конструктор
Это не thread-safe.

Affected: RiskState.java:16-17, RiskStateReducer.java:69-84 Impact: Гонки при многопоточном доступе к RiskState.

RISK-2: RiskService.publish() — двойная идемпотентность
Описание: RiskService.publish() (L138-171):

Проверяет riskStatePort.isEventProcessed(eventId) (L152) — одна проверка
RiskStateReducer.reduce() внутри проверяет processedEventIds.contains(event.getEventId()) (L25) — вторая проверка
Две проверки между разными источниками: riskStatePort.isEventProcessed() смотрит в таблицу risk_events, а processedEventIds — в поле RiskStateEntity. При расхождении поведение непредсказуемо.

Affected: RiskService.java:152, RiskStateReducer.java:25, RiskStateRepositoryAdapter.java:51-53 Impact: Возможен пропуск события при неконсистентности.

RISK-3: RiskService.reserve() — контекст создаётся ПОСЛЕ проверки
Описание: RiskService.reserve() (L175-194):

Читает state (L177)
Проверяет RiskPolicy.canReserve() (L178)
Создаёт eventId (L181) — ПОСЛЕ проверки
Вызывает reducer.reduce() и markEventProcessed()
Между чтением состояния и записью нет блокировки. Это read-modify-write race condition — идентичен CRIT-4, но для вызова reserve() напрямую.

Affected: RiskService.java:175-194 Impact: Превышение резервирования при конкурентных вызовах.

RISK-4: Нет проверки рисков перед фазой EXECUTE
Описание: OrderExecutionHandler.consume() (L57-114):

Phase 1: CLAIM — захват ордера (L70)
Phase 2: IO — биржевой вызов (L88-94)
Phase 3: COMMIT — фиксация (L99)
Нет повторной проверки рисков перед отправкой на биржу. Ордер мог быть одобрен RiskService на этапе создания, но к моменту исполнения состояние RiskEngine могло измениться (halt, недостаток капитала). Проверка на свежесть аппрува (isApprovalFresh) существует в DefaultRiskManager, но не вызывается из OrderExecutionHandler.

Affected: OrderExecutionHandler.java:88-94, DefaultRiskManager.java:70-79 Impact: Ордер может быть отправлен на биржу после halt системы.

RISK-5: Авто-Halt мутирует состояние вне transaction boundary
Описание: RiskStateReducer.checkAndApplyAutoHalt() (L69-84) вызывает state.setHalted(true) — мутирует переданный state. Это происходит внутри reduce(), который вызывается из RiskService.publish(). Но если publish() вызывается без активной транзакции, состояние меняется в памяти без персистенции.

Affected: RiskStateReducer.java:69-84 Impact: Auto-halt срабатывает в памяти, но не сохраняется в БД.

7. RECOMMENDED REFACTOR PLAN
   Phase 0: Stop the Bleeding (немедленно)
#	Действие	Приоритет
1	Удалить TradingPipeline.java — полный дубликат TradeService. Оставить только TradeService	КРИТ
2	Убрать синхронный вызов equityService.onTradeCreated() из TradeService.onOrderFilled() — оставить только Outbox-путь	КРИТ
3	Добавить @Transactional(propagation = REQUIRES_NEW) на commitExecution()	КРИТ
4	Добавить PESSIMISTIC_WRITE lock на RiskState при evaluateSignal	КРИТ
Phase 1: Fix Position SSOT
#	Действие
5	Унифицировать Position модели: оставить PositionState как SSOT. Удалить Position (@Value). PositionEntity должен быть тонкой JPA-проекцией
6	Перенести всю логику расчёта в PositionReducer. PositionEntity.applyTrade() удалить. PositionRebuildService должен использовать PositionReducer.reduce()
7	Унифицировать averagePrice/avgEntryPrice/entryPrice → единое имя averageEntryPrice
8	Сделать PositionService.updatePosition() чисто Outbox-driven — убрать синхронные вызовы
Phase 2: Fix Transaction Boundaries
#	Действие
9	Перевести Outbox на polling с SELECT ... FOR UPDATE SKIP LOCKED + exactly-once на уровне consumer
10	Добавить distributed lock (Redis/DB) для RiskState read-modify-write
11	Исправить syncBalance/emergencyStop/resumeTrading — пропускать через RiskStateReducer с event
12	Убрать OutboxService из конструктора RiskService
Phase 3: Fix Event Flow
#	Действие
13	Убрать дублирование ORDER_EXECUTED обработки — или commitExecution обновляет состояние и публикует событие, или OrderExecutedEventHandler. Не оба
14	Исправить handleAlreadyProcessed — использовать orderId, не signalId
15	Удалить OutboxDispatcher (dead code)
Phase 4: Harden Risk Engine
#	Действие
16	RiskState → true immutable record (убрать @Data, убрать setters, заменить setHalted() на toBuilder().halted(true).build())
17	Добавить pre-execution risk check в OrderExecutionHandler.consume() перед биржевым вызовом
18	Объединить идемпотентность RiskService в единый механизм — только processedEventIds или только risk_events таблица, не оба
Итог: Система имеет высокий уровень зрелости identity-модели (IdentityFactory, ExecutionContext, детерминированные ID), но страдает от трёх фундаментальных проблем: (1) дублирующиеся execution paths, (2) расхождение Position-алгоритмов между live/rebuild, (3) отсутствие распределённой блокировки на RiskState. Эти три проблемы — первоочередные цели для предотвращения data corruption.