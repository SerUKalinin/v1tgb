# IDENTITY_SSOT_MANIFEST.md

## СТАТУС: SSOT LOCKED 🔒

Данный документ фиксирует архитектуру идентификации системы как неизменяемую (Immutable). Любые изменения в модели идентификации требуют отдельного процесса RFC (Request for Comments) и оценки влияния на детерминизм системы.

### 1. ФУНДАМЕНТАЛЬНЫЕ ПРАВИЛА (CORE RULES)

*   **SignalId Root Rule**: `signalId` является неизменяемым корнем (Root Identity) любого бизнес-процесса. Он генерируется один раз на входе в систему через `IdentityFactory.newRoot()`.
*   **Deterministic Derivation Rule**: Все производные идентификаторы (`orderId`, `executionId`, `eventId`) ОБЯЗАНЫ вычисляться детерминированно на основе родительского ID и соли через `IdentityFactory.derive()`. Использование `UUID.randomUUID()` в других местах ЗАПРЕЩЕНО.
*   **Context-Only Propagation Rule**: `ExecutionContext` является единственным легитимным носителем идентичности. Передача UUID отдельно от контекста или его ручная распаковка в бизнес-логике ЗАПРЕЩЕНЫ.

### 2. АРХИТЕКТУРНЫЕ ОГРАНИЧЕНИЯ (CONSTRAINTS)

*   **Outbox Mirroring**: Таблица Outbox является прямым отражением `ExecutionContext`. `eventId` в базе всегда равен `executionId` из контекста.
*   **State Machine Pass-Through**: Переходы состояний доменных моделей обязаны принимать и пробрасывать `ExecutionContext` без модификации его идентификационных полей.
*   **Immutability**: Все классы контекстов (`IdentityContext`, `ExecutionAttemptContext`, `BusinessContext`, `ExecutionContext`) являются Java Records или неизменяемыми классами.

### 3. ЗАПРЕЩЕННЫЕ ПАТТЕРНЫ (FORBIDDEN)

*   ❌ Регенерация ID при ретраях или восстановлении системы.
*   ❌ Использование сеттеров для полей, заканчивающихся на `Id`.
*   ❌ Создание "fallback" или "temporary" идентичностей.
*   ❌ Маппинг идентификаторов в слоях инфраструктуры (только прямой проброс).

---
**Любое нарушение данных правил блокируется на уровне архитектурных тестов (IdentityArchitectureTest).**
