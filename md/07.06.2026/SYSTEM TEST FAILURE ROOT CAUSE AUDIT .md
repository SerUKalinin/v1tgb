SYSTEM TEST FAILURE ROOT CAUSE AUDIT
Проект: d:\v1 TGBOT | Профиль: test | Spring Boot: 3.3.4

ШАГ 1. Упавшие тесты
Из предоставленных логов зафиксировано 3 упавших тест-метода в 2 тест-классах:

#	Класс	Метод	Статус
1	ArchitectureInvarianceTest	shouldPreventDirectExecutionEngineCallWithRawRequest()	FAILED
2	ArchitectureInvarianceTest	shouldPreventApprovedOrderCreationOutsideRiskPackage()	FAILED
3	ConcurrentRiskStressTest	shouldPreventDoubleSpendUnderHighConcurrency()	FAILED
Все 3 наследуют BaseIntegrationTest → поднимают полный Spring-контекст через @SpringBootTest.

ШАГ 2. Root Cause каждого теста
Все 3 теста падают с идентичной цепочкой исключений — до выполнения тела теста. Контекст не может стартовать.

TEST #1: ArchitectureInvarianceTest.shouldPreventDirectExecutionEngineCallWithRawRequest()
FAILURE CHAIN:

IllegalStateException: Failed to load ApplicationContext
→ UnsatisfiedDependencyException: tradingSystemBootstrapper (constructor param 1)
→ UnsatisfiedDependencyException: riskStateRecoveryService (constructor param 3)
→ UnsatisfiedDependencyException: riskEngine (constructor param 0)
→ UnsatisfiedDependencyException: riskService (RiskDomainConfig.riskService() param 0)
→ NoSuchBeanDefinitionException: RiskStatePort
ROOT CAUSE: No qualifying bean of type 'com.tradingbot.domain.risk.RiskStatePort' available

SOURCE:

src/main/java/com/tradingbot/config/RiskDomainConfig.java:23-24 — метод riskService(...) требует RiskStatePort port
src/main/java/com/tradingbot/infrastructure/persistence/repository/JpaRiskRepository.java:20 — реализация существует (implements RiskStatePort), но отсутствует Spring-аннотация @Repository
TEST #2: ArchitectureInvarianceTest.shouldPreventApprovedOrderCreationOutsideRiskPackage()
Идентично TEST #1. Оба теста в одном классе → один контекст → одна ошибка.

ROOT CAUSE: Та же — NoSuchBeanDefinitionException: RiskStatePort

TEST #3: ConcurrentRiskStressTest.shouldPreventDoubleSpendUnderHighConcurrency()
Идентично TEST #1. Тот же механимз падения контекста.

ROOT CAUSE: Та же — NoSuchBeanDefinitionException: RiskStatePort

ШАГ 3. Аудит отсутствующего бина
Какой бин отсутствует
com.tradingbot.domain.risk.RiskStatePort
Кто его требует (полная цепочка зависимостей)
RiskDomainConfig.riskService(RiskStatePort port, ...)       ← требует RiskStatePort    [RiskDomainConfig.java:23]
↑
RiskEngine(RiskService riskService)                         ← требует RiskService       [RiskEngine.java:26]
↑
RiskStateRecoveryService(RiskEngine riskEngine, ...)        ← требует RiskEngine        [RiskStateRecoveryService.java:30]
↑
TradingSystemBootstrapper(RiskStateRecoveryService, ...)    ← требует RecoveryService   [TradingSystemBootstrapper.java:22]
Альтернативная прямая цепочка (для ConcurrentRiskStressTest):

RiskEngine(RiskService riskService)   ← @Autowired напрямую
↑
RiskDomainConfig.riskService(RiskStatePort port, ...)
↑
RiskStatePort ← MISSING
Почему Spring не может его создать
Критерий	Статус
Интерфейс RiskStatePort существует	✅ RiskStatePort.java‎
Реализация существует	✅ JpaRiskRepository implements RiskStatePort в src/main/java/com/tradingbot/infrastructure/persistence/repository/JpaRiskRepository.java:20
@Repository / @Component / @Service на реализации	❌ ОТСУТСТВУЕТ
@Bean метод, создающий RiskStatePort	❌ ОТСУТСТВУЕТ
Альтернативная реализация RiskStateRepositoryAdapter	❌ ЗАКОММЕНТИРОВАНА (RiskStateRepositoryAdapter.java:25)
@MockBean RiskStatePort в BaseIntegrationTest	❌ ОТСУТСТВУЕТ
Подтверждённый факт: В JpaRiskRepository.java:12 есть импорт org.springframework.stereotype.Repository, но сама аннотация не стоит на классе (строка 18-20). Класс аннотирован только @RequiredArgsConstructor и @Slf4j.

ШАГ 4. NullPointerException
В предоставленных логах нет NullPointerException. Все ошибки — это NoSuchBeanDefinitionException.

ШАГ 5. SQL-ошибки
В предоставленных логах нет SQL-ошибок. Hibernate DDL отрабатывает корректно (H2 in-memory), все таблицы создаются, drop → create проходит без ошибок.

ШАГ 6. Дерево Root Failures
ROOT FAILURE #1: NoSuchBeanDefinitionException — RiskStatePort
├── ArchitectureInvarianceTest.shouldPreventDirectExecutionEngineCallWithRawRequest()   ← каскадное
├── ArchitectureInvarianceTest.shouldPreventApprovedOrderCreationOutsideRiskPackage()   ← каскадное
└── ConcurrentRiskStressTest.shouldPreventDoubleSpendUnderHighConcurrency()             ← каскадное
Только 1 root cause. Нет реальных багов в бизнес-логике тестов. Все 3 падают до выполнения тестового метода (prepareTestInstance → loadContext).

ШАГ 7. Итог
Root Cause	Количество тестов	Severity	Fix Complexity
JpaRiskRepository не имеет @Repository → Spring не создаёт бин RiskStatePort	3	CRITICAL	TRIVIAL
SAFE TO MERGE: NO
BLOCKERS:
JpaRiskRepository.java:18 — отсутствует @Repository. Класс реализует RiskStatePort, импортирует org.springframework.stereotype.Repository (строка 12), но аннотация не стоит. Необходимо добавить @Repository перед объявлением класса.

BaseIntegrationTest.java — отсутствует @MockBean RiskStatePort. Даже после фикса №1 тесты с @SpringBootTest могут требовать заглушку, если реальный бин требует БД/бинance-подключение. Рекомендуется добавить: @MockBean protected RiskStatePort riskStatePort;

RiskStateRepositoryAdapter.java:25 — закомментирован. Если это была планируемая реализация, её нужно либо разкомментировать и подключить, либо удалить dead code.