Ты выступаешь как Senior/Staff Java Architect и должен провести полный аудит и довести систему до состояния, готового к началу Stage 4 (Strategy), без архитектурного и технического долга.

Контекст системы:

* Java + Spring Boot
* Архитектура: Modular Monolith + Event-Driven
* Есть: OMS (FSM как state authority), Execution через async listener, PostgreSQL (source of truth)
* Частично реализованы: Risk Engine (per-trade), fencing, FSM
* Интеграция с Binance (будет multi-exchange)

---

# 🎯 ЦЕЛЬ

Довести систему до состояния:

👉 Strategy можно внедрять БЕЗ переписывания ядра
👉 Система НЕ привязана к конкретной бирже
👉 Все критические риски (финансовые и архитектурные) закрыты

---

# 📊 ФОРМАТ ОЦЕНКИ (ОБЯЗАТЕЛЬНО)

Для каждого пункта:

* ✅ DONE — полностью реализовано
* ⚠️ PARTIAL — частично / есть дыры
* ❌ NOT IMPLEMENTED — отсутствует

И обязательно:

* ПОЧЕМУ такой статус
* ГДЕ это в коде (или отсутствует)
* КАК исправить (конкретно)

---

# 🧠 БЛОК 1. RISK ENGINE (Stage 3)

## 1. Risk как глобальный gatekeeper

* нет ни одного пути к Execution без Risk
* retry / recovery / reconciliation проходят через Risk

## 2. Portfolio-level риск

* total exposure
* total risk
* equity
* ограничения по инструментам

## 3. Kill-switch

* ACTIVE / BLOCKED / EMERGENCY
* блокирует ВСЁ
* ручной и авто-триггер

## 4. Cooldown как state

* не if, а состояние
* авто-снятие
* участвует в Risk

## 5. Retry safety

* retry проходит через Risk
* учитывает новые условия

## 6. Идемпотентность Risk

* детерминирован
* без IO
* без side-effects

## 7. Контракт Risk

* RiskRequest / RiskDecision
* нет дублирования логики

## 8. Встраивание в pipeline

* Risk ДО DB / event / execution

## 9. Логирование

* reason
* correlationId
* полный trace

## 10. Тесты

* unit / integration / edge cases

---

# 🧠 БЛОК 2. DELIVERY & CONSISTENCY (Stage 3.5)

## 1. Outbox pattern

* события НЕ теряются
* DB commit + event в одной транзакции

## 2. Event delivery

* гарантированная доставка в execution
* retry delivery

## 3. Idempotent execution

* повтор не создаёт дубликаты

## 4. Recovery процессы

* система поднимает зависшие ордера

---

# 🧠 БЛОК 3. EXCHANGE ABSTRACTION

## 1. ExecutionEngine интерфейс

* placeOrder
* cancelOrder
* getStatus

## 2. MarketDataProvider

* price
* candles

## 3. ExchangeMetadata

* lot size
* min notional
* filters

## 4. Удаление зависимости от Binance

Проверить:

* нет прямых вызовов Binance вне adapter слоя

---

# 🧠 БЛОК 4. MARKET DATA LAYER

## 1. MarketSnapshot

* price
* candles
* timestamp

## 2. Отделение данных от стратегии

* стратегия НЕ вызывает API

## 3. Подготовка к WebSocket

* данные идут через cache (in-memory / Redis)

---

# 🧠 БЛОК 5. STRATEGY READY CONTRACT

## 1. Чистый контракт стратегии

Decision decide(MarketSnapshot snapshot, PortfolioState state)

## 2. Запрещено:

* HTTP вызовы
* Binance
* DB доступ

---

# 🧠 БЛОК 6. EXECUTION ISOLATION

* Execution = адаптер к бирже
* не содержит бизнес-логики
* не влияет на решения системы

---

# 🧠 БЛОК 7. STATE CONSISTENCY

* OMS = source of truth
* externalOrderId хранится
* partial fills поддерживаются (минимально)

---

# ⚠️ АНТИ-ПАТТЕРНЫ

Отметь если найдешь:

* обход Risk
* прямые вызовы Binance из бизнес-логики
* потеря событий
* стратегия знает про биржу
* IO внутри Risk
* in-memory состояние без консистентности

---

# 📦 ЧТО НУЖНО СДЕЛАТЬ В ОТВЕТЕ

1. Полный аудит по всем блокам (статусы)
2. Список критических дыр (что блокирует Stage 4)
3. Конкретные изменения:

  * какие классы добавить
  * какие изменить
4. Примеры кода:

  * Risk
  * Outbox
  * ExecutionEngine
  * MarketDataProvider
  * Strategy contract
5. Финальный pipeline (как работает система после изменений)
6. Пошаговый план закрытия всех дыр

---

# 📊 FINAL CRITERIA OF DONE

Система готова к Stage 4 только если:

* нет потери событий
* Risk полностью контролирует систему
* стратегия изолирована от биржи
* можно подключить новую биржу без переписывания логики
* execution идемпотентен
* система устойчива к падениям

---

Важно:

Ты не предлагаешь “улучшения”.

Ты:

👉 доводишь систему до production-ready состояния
👉 убираешь ВСЕ архитектурные блокеры
👉 готовишь фундамент для стратегии

Финальная цель:

👉 система, которую можно масштабировать и безопасно запускать в реальных условиях
