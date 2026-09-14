SPRING CONTEXT STARTUP FAILURE AUDIT
SECTION 1 — PROPERTY RESOLUTION
PROPERTY	FILE	REQUIRED	DEFAULT VALUE	PROFILE	CRASH?
BINANCE_API_KEY	application-testnet.yaml	ДА	—	testnet	CRITICAL
BINANCE_SECRET_KEY	application-testnet.yaml	ДА	—	testnet	CRITICAL
binance.base-url	application-testnet.yaml	Нет	https://api.binance.com	testnet	Нет
binance.api-key	test application.yml	Да	test-api-key	test/backtest	Нет (задан)
binance.secret-key	test application.yml	Да	test-secret-key	test/backtest	Нет (задан)
telegram.bot.token	application.yaml	Да	8064498072:... (хардкод)	все	Нет
telegram.bot.username	application.yaml	Да	v1TGBot (хардкод)	все	Нет
telegram.bot.admin-id	application.yaml	Да	403753468 (хардкод)	все	Нет
app.outbox.enabled	OutboxProcessor	Нет	true	все	Нет
app.outbox.scan-interval	OutboxProcessor	Нет	500	все	Нет
exchange.metadata.refresh-rate	ExchangeMetadataService	Нет	21600000 (6h)	все	Нет
trading.update-rate-ms	test application.yml + MarketScheduler	Нет	5000	test/backtest	Нет
spring.datasource.url	application.yaml	Да	jdbc:postgresql://...	main	HIGH (нужна БД)
Вывод SECTION 1
Единственные свойства, которые уронят контекст в testnet-профиле:

${BINANCE_API_KEY} — не установлена переменная окружения
${BINANCE_SECRET_KEY} — не установлена переменная окружения
В тестовом (test/backtest) профиле: оба свойства переопределены в application.yml‎
→ BinanceClient создаётся без ошибок. Тесты падают по другой причине.

SECTION 2 — TEST PROFILE AUDIT
application.yml‎
(ПОЛНОСТЬЮ)
spring:
main:
allow-bean-definition-overriding: true
profiles:
active: backtest
datasource:
url: jdbc:h2:mem:testdb;...
username: sa
password:
driver-class-name: org.h2.Driver
jpa:
database-platform: org.hibernate.dialect.H2Dialect
hibernate:
ddl-auto: create-drop
show-sql: true
flyway:
enabled: false

binance:
api-key: test-api-key
secret-key: test-secret-key
base-url: https://api.binance.com

trading:
update-rate-ms: 60000

app:
outbox:
enabled: false
scan-interval: 1000000
application-test.yml / application-test.yaml
Файл существует, но ПУСТОЙ (1 строка). Профиль test не имеет собственной конфигурации.

application-backtest.yml
Файл существует, но ПУСТОЙ (1 строка).

Все @ActiveProfiles
Тест/базовый класс	Профиль
TradingBotApplicationTests	backtest
BaseIntegrationTest (базовый для многих)	test
PartialTransactionRollbackSafetyTest	test
ExchangeResilienceIntegrationTest	test
CrossNodeClockDriftResilienceTest	test
BinanceClientOrderIdTest	test
RiskBootstrapIntegrationTest	test
RiskRestartResilienceTest	test
RiskRecoveryDeterminismTest	test
ExecutionConcurrencySafetyTest	test
PositionRebuildFromTradesTest	test
Все @TestConfiguration
Только один — PartialTransactionRollbackSafetyTest.TestConfig.

Все @MockBean
Тест	MockBean
BaseIntegrationTest	TradingTelegramBot, RiskStatePort
ExchangeResilienceIntegrationTest	OutboxEventRouter
OutboxCrashResilienceTest	OutboxEventRouter
EventDrivenChaosIntegrationTest	OutboxService, RiskEngine
LedgerReliabilityIntegrationTest	OutboxService, RiskEngine
Ответы
1. Должен ли BinanceClient создаваться в test профиле? Да. BinanceClient НЕ имеет @Profile → создаётся всегда. В тестовом application.yml заданы binance.api-key=test-api-key и binance.secret-key=test-secret-key → создаётся успешно.

2. Есть ли @Profile("!test")? Да:

TelegramConfig — @Profile("!test")
TradingTelegramBot — @Profile("!test")
3. Есть ли @ConditionalOnProperty? Нет. Ни одного @ConditionalOnProperty во всём проекте.

4. Есть ли способ отключить Binance интеграцию в тестах? Нет. BinanceClient не имеет ни @Profile, ни @ConditionalOnProperty. Он всегда создаётся. Однако в тестах ExchangeMetadataService.onApplicationReady() делает реальный HTTP-запрос к https://api.binance.com/api/v3/exchangeInfo.

SECTION 3 — BINANCE DEPENDENCY CHAIN
TradingSystemBootstrapper (@Service)
├── SystemStateManager
├── RiskStateRecoveryService (@Service)
│   ├── RiskEventRepository
│   ├── RiskSnapshotRepository
│   ├── RiskReservationLogRepository
│   ├── RiskEngine (@Component) ─────────────────────────────┐
│   ├── RiskStateReducer                                      │
│   ├── ObjectMapper                                          │
│   ├── RiskReconciler                                        │
│   ├── ExchangeOrderQueryService (@Service)                  │
│   │   └── ... (has @Profile restrictions)                   │
│   └── SystemStateManager                                    │
├── MarketDataService (@Service)                              │
│   ├── BinanceMarketDataClient (@Component)                  │
│   │   └── BinanceClient (@Component, no @Profile) ◄─────────┤
│   ├── MarketDataCache                                       │
│   ├── CandleTransitionDetector                              │
│   ├── ApplicationEventPublisher                             │
│   └── RiskEngine ──────────────────────────────────────────┘
├── RiskEngine ───────────────────────────────────────────────┐
│   └── RiskService (@Bean in RiskDomainConfig)                │
│       ├── RiskStatePort       (MockBean в тестах)            │
│       ├── RiskStateReducer                                   │
│       ├── RiskReservationLogPort                             │
│       ├── ExchangeFeasibilityPort = BinanceFeasibilityValidator│
│       │   └── ExchangeMetadataService                       │
│       │       └── BinanceClient ◄───────────────────────────┘
│       ├── OrderNormalizationService = BinanceFeasibilityValidator
│       ├── ExecutionLogger
│       └── OutboxService
├── ExchangeOrderQueryService
│   └── ExchangeOrderQueryServiceImpl (@Service)
│       └── BinanceClient ◄───────────────────────────────────┐
└── ApplicationEventPublisher                                 │
│
ВСЕ ПУТИ ВЕДУТ К ─────────────────────────┘
BinanceClient
Bean'ы, которые невозможно создать без BinanceClient:
#	Bean	Слой	Зависимость
1	BinanceClient	Infrastructure	—
2	ExchangeMetadataService	Infrastructure	BinanceClient
3	BinanceMarketDataClient	Infrastructure	BinanceClient
4	ExchangeOrderQueryServiceImpl	Infrastructure	BinanceClient
5	BinanceFeasibilityValidator	Infrastructure	ExchangeMetadataService
6	MarketDataService	Application	BinanceMarketDataClient
7	RiskService	Domain	BinanceFeasibilityValidator
8	RiskEngine	Application	RiskService
9	RiskStateRecoveryService	Application	RiskEngine
10	TradingSystemBootstrapper	Application	RiskStateRecoveryService + MarketDataService + RiskEngine
SECTION 4 — STARTUP BLOCKERS
A. BINANCE_API_KEY / BINANCE_SECRET_KEY
Параметр	Значение
Severity	CRITICAL
Файл	application-testnet.yaml строка 2-3
Bean	BinanceClient
Эффект	Падает ВЕСЬ контекст в профиле testnet
В тестах	НЕ падает (значения переопределены в test application.yml)
B. ExchangeMetadataService.onApplicationReady() — реальный HTTP-запрос в тестах
Параметр	Значение
Severity	HIGH
Файл	ExchangeMetadataService.java:26-29
Эффект	При старте контекста (в т.ч. в тестах) @EventListener(ApplicationReadyEvent.class) делает refreshCache() → реальный HTTP GET к https://api.binance.com/api/v3/exchangeInfo. Без интернета/прокси — контекст НЕ упадёт (исключение ловится в catch), но кастомная инициализация сломана.
В тестах	Не уронит контекст, но может замедлить старт или создать шум в логах.
C. TradingSystemBootstrapper.onApplicationReady() — цепочка восстановления
Параметр	Значение
Severity	HIGH
Файл	TradingSystemBootstrapper.java:28-31
Эффект	При старте вызывает riskRecoveryService.recover(), затем exchangeQueryService.getAvailableBalance("USDT"), затем marketDataService.warmUpAll(). Каждый из этих шагов требует БД. Контекст падает если БД недоступна.
В тестах	H2 in-memory → БД доступна. Но exchangeQueryService.getAvailableBalance() может упасть.
D. MarketScheduler.refreshMarketData() — scheduled task
Параметр	Значение
Severity	MEDIUM
Файл	MarketScheduler.java:52
Эффект	@Scheduled задача требует MarketDataService → BinanceClient. В тестах outbox отключен, но сам scheduler НЕ отключен.
E. OutboxProcessor scheduled tasks
Параметр	Значение
Severity	LOW
Файл	OutboxProcessor.java:57-61
Эффект	@Scheduled с app.outbox.enabled:true по умолчанию, но в тестах app.outbox.enabled: false. Безопасно.
F. Telegram при @Profile("!test")
Параметр	Значение
Severity	LOW
Эффект	Telegram бот и конфигурация отключены в тестах через @Profile("!test") + @MockBean. Безопасно.
G. Отсутствие application-test.yml
Параметр	Значение
Severity	MEDIUM
Эффект	Тесты используют @ActiveProfiles("test"), но файл application-test.yml ПУСТ. Все настройки берутся из test application.yml (который активирует backtest профиль). Это работает случайно — spring.profiles.active: backtest в test application.yml переопределяет testnet из main.
SECTION 5 — FIX STRATEGY
Вариант A: Минимальный фикс, чтобы тесты стартовали
Тесты уже используют application.yml‎
где binance.api-key=test-api-key. Исправлять нужно ТОЛЬКО для bootRun (testnet профиль):

# Установить переменные окружения:
export BINANCE_API_KEY="your-testnet-key"
export BINANCE_SECRET_KEY="your-testnet-secret"
Или в application-testnet.yaml заменить:

Файл: application-testnet.yaml‎
Строки 2-3:

# БЫЛО:
api-key: ${BINANCE_API_KEY}
secret-key: ${BINANCE_SECRET_KEY}

# СТАЛО:
api-key: ${BINANCE_API_KEY:test-api-key-placeholder}
secret-key: ${BINANCE_SECRET_KEY:test-secret-key-placeholder}
Bean'ы, которые перестанут создаваться: никакие (все создадутся, но с placeholder-ключами, API Binance будет возвращать 401 — это нормально для тестнета без реальных ключей).

Какие тесты восстановятся: bootRun в testnet-профиле запустится (без реальной торговли).

Вариант B: Архитектурно правильный фикс для PRE-INFRA OMS
Шаг 1. Добавить @Profile на BinanceClient

Файл: BinanceClient.java‎
Строка 19-21:

// БЫЛО:
@Slf4j
@Component
public class BinanceClient {

// СТАЛО:
@Slf4j
@Component
@Profile({"testnet", "prod", "live"})
public class BinanceClient {
Шаг 2. ExchangeMetadataService должен быть условным

Файл: ExchangeMetadataService.java‎
Строка 19-21:

// БЫЛО:
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeMetadataService {

// СТАЛО:
@Slf4j
@Service
@RequiredArgsConstructor
@Profile({"testnet", "prod", "live"})
public class ExchangeMetadataService {
Шаг 3. BinanceFeasibilityValidator должен быть условным

Файл: BinanceFeasibilityValidator.java‎
Строка 12-15:

// БЫЛО:
@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceFeasibilityValidator implements ExchangeFeasibilityPort, OrderNormalizationService {

// СТАЛО:
@Slf4j
@Component
@RequiredArgsConstructor
@Profile({"testnet", "prod", "live"})
public class BinanceFeasibilityValidator implements ExchangeFeasibilityPort, OrderNormalizationService {
Шаг 4. Создать заглушки для ExchangeFeasibilityPort и OrderNormalizationService в test/backtest профилях

Новый файл: src/main/java/com/tradingbot/infrastructure/execution/fake/FakeFeasibilityValidator.java

@Slf4j
@Component
@Profile({"test", "backtest"})
public class FakeFeasibilityValidator implements ExchangeFeasibilityPort, OrderNormalizationService {
@Override
public NormalizedOrder normalize(FeasibilityRequest request) {
return new NormalizedOrder(request.getSymbol(), request.getQuantity(), request.getPrice());
}
@Override
public FeasibilityResult check(FeasibilityRequest request) {
return FeasibilityResult.success();
}
}
Шаг 5. Аналогично для BinanceMarketDataClient

Файл: BinanceMarketDataClient.java‎

// ДОБАВИТЬ:
@Profile({"testnet", "prod", "live"})
(FakeMarketDataClient уже существует с @Profile("backtest"))

Шаг 6. ExchangeOrderQueryServiceImpl

Файл: ExchangeOrderQueryServiceImpl.java‎

// ДОБАВИТЬ:
@Profile({"testnet", "prod", "live"})
Шаг 7. Добавить application-test.yml с корректным содержимым

Файл: src/main/resources/application-test.yml (заменить пустой файл)

SECTION 6 — EXPECTED RECOVERY
Если исправить ТОЛЬКО BINANCE_API_KEY:
Сколько тестов вероятно восстановится: 0 (ноль).

Почему: Тесты НЕ падают от отсутствия BINANCE_API_KEY. Тесты используют application.yml‎
где:

binance:
api-key: test-api-key
secret-key: test-secret-key
Все @Value("${binance.api-key}") разрешаются корректно для тестов.

Реальный статус тестов: для определения необходимо запустить gradlew test. Тесты используют H2 in-memory базу, Flyway отключен, app.outbox.enabled: false. Вероятные причины падения тестов (помимо BINANCE_API_KEY):

TradingSystemBootstrapper.onApplicationReady() — при ApplicationReadyEvent пытается выполнить riskRecoveryService.recover() → exchangeQueryService.getAvailableBalance("USDT") → этот метод делает реальный HTTP-запрос к Binance с test-api-key. Контекст не упадёт, но вызовет ошибку в логах.

ExchangeMetadataService.onApplicationReady() — аналогично, реальный HTTP-запрос к Binance /api/v3/exchangeInfo с фейковым ключом → 401/403 ошибка в логах, но контекст НЕ падает (catch в коде).

ArchUnit тесты: IdentityArchitectureTest проверяет, что:

UUID.randomUUID() вызывается только в com.tradingbot.tracing.. → SignalEntity.onCreate() (строка 58) в infrastructure.persistence.entity нарушает это правило — вызывает UUID.randomUUID() вне разрешённого пакета. Этот тест упадёт.
Поля *Id в domain/tracing должны быть final → User.id и User.chatId НЕ final (из-за @Data). Этот тест упадёт.
Какие тесты всё ещё будут падать:

Тест	Причина
IdentityArchitectureTest.identity_must_be_deterministic	SignalEntity.onCreate() вызывает UUID.randomUUID() в пакете infrastructure.persistence.entity
IdentityArchitectureTest.identity_fields_must_be_immutable	User.id, User.chatId не final
Потенциально TradingBotApplicationTests	Зависит от того, падает ли ApplicationReadyEvent listener с фейковым Binance API
Какие ArchUnit нарушения останутся:

identity_must_be_deterministic — SignalEntity нарушает
identity_fields_must_be_immutable — User нарушает
services_must_use_execution_context — проверить не можем без запуска, но RiskEngine.reserve/release принимает ExecutionContext → должно быть OK
identity_creation_restriction — проверить не можем без запуска
Итог: BINANCE_API_KEY — это проблема bootRun (testnet профиль), а НЕ тестов. Для реального восстановления тестов нужно фиксить:

ArchUnit нарушения (SignalEntity, User)
Возможно — адаптировать ExchangeMetadataService / ExchangeOrderQueryServiceImpl чтобы не падали на фейковых ключах в тестах (или добавить @Profile)