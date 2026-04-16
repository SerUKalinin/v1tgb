# План реализации: Stage 3 — Production Risk Engine

Этот план описывает переход к детерминированному управлению рисками на основе событий (Event Sourcing) и атомарных обновлений состояния.

## PHASE 1: Foundation (Atomic State)
- [x] **Шаг 1.1: RiskState & Events**
    - Создать `RiskState` (immutable POJO).
    - Определить события: `TradeExecutedEvent`, `PriceUpdatedEvent`.
- [x] **Шаг 1.2: RiskStateReducer**
    - Реализовать чистую функцию `(State, Event) -> State`.
- [x] **Шаг 1.3: RiskStateStore**
    - Атомарное хранилище с использованием `AtomicReference`.
## PHASE 2: Rules & Validation
- [x] **Шаг 2.1: RiskRules Engine**
    - Интерфейс `RiskRule`.
    - Реализация `DailyLossRule`, `DrawdownRule`.
- [x] **Шаг 2.2: ExchangeFilterService**
    - Fail-fast валидация перед отправкой ордера.
    - Per-symbol locking (ConcurrentHashMap).

## PHASE 3: Integration & Verification
- [x] **Шаг 3.1: RiskManager Refactoring**
    - Интеграция Store и Reducer в `DefaultRiskManager`.
- [x] **Шаг 3.2: Тестирование**
    - `RiskStateReplayTest` (проверка детерминизма).
    - `RiskManagerConcurrencyTest` (проверка потокобезопасности).

## PHASE 4: Hybrid Event Sourcing & Recovery
- [x] **Шаг 4.1: Idempotency Guards**
    - Добавлены `processedEventIds` в `RiskState`.
- [x] **Шаг 4.2: RiskStateRecoveryService**
    - Восстановление состояния из БД при запуске.
- [x] **Шаг 4.3: TradeService Integration**
    - Автоматическое обновление риска при каждой сделке.
