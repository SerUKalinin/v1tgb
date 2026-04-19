# 🚨 STAGE 3 COMPLETION GATE (MASTER RULE FILE)

## 📌 НАЗНАЧЕНИЕ

Этот файл является единственным источником истины для определения:

👉 готова ли система к Stage 4 (Strategy Layer)

---

# 🧠 КОНТЕКСТ СИСТЕМЫ

Архитектура:

- Java + Spring Boot
- Modular Monolith + Event-Driven
- OMS = State Authority
- Execution = async adapter layer
- PostgreSQL = Source of Truth
- Risk Engine = financial gatekeeper
- Binance = external adapter (future multi-exchange)

---

# 🎯 ГЛАВНАЯ ЦЕЛЬ STAGE 3

Система считается завершённой только если:

✔ Strategy можно внедрять без изменения ядра  
✔ система НЕ привязана к бирже  
✔ нет потерь событий  
✔ Risk полностью контролирует execution  
✔ execution идемпотентен и безопасен

---

# 📊 ОБЯЗАТЕЛЬНЫЕ КРИТЕРИИ

## 1. Risk Engine (CRITICAL)

✔ контролирует ВСЕ execution paths  
✔ нет обхода Risk  
✔ retry / recovery / reconciliation проходят через Risk  
✔ Risk = детерминированная функция  
✔ NO IO / NO side effects

---

## 2. Execution Layer

✔ Execution = тупой адаптер биржи  
✔ не содержит бизнес-логики  
✔ не принимает решений  
✔ не влияет на Risk или Strategy

---

## 3. Event Consistency

✔ нет потери событий  
✔ система устойчива к restart  
✔ delivery гарантирован (если есть Outbox)  
✔ retry-safe event pipeline

---

## 4. Strategy Isolation

✔ Strategy не знает про биржу  
✔ Strategy не делает HTTP / DB calls  
✔ Strategy = чистая функция:


Decision decide(MarketSnapshot, PortfolioState)


---

## 5. OMS (Source of Truth)

✔ OMS = единственный источник состояния  
✔ externalOrderId хранится  
✔ state transitions консистентны

---

## 6. Exchange Abstraction

✔ нет прямых Binance вызовов в бизнес-логике  
✔ ExecutionEngine абстрагирует биржи  
✔ система готова к multi-exchange

---

## 7. State Consistency

✔ нет in-memory state без консистентности  
✔ recovery механизм существует  
✔ система восстанавливает open orders

---

# ⚠️ CRITICAL BLOCKERS (СТОП СИСТЕМЫ)

Если найдено хоть одно:

- обход Risk Engine
- IO внутри Risk
- Strategy знает про биржу
- Execution содержит бизнес-логику
- потеря событий
- нет восстановления состояния
- прямые Binance вызовы вне adapter слоя
- неконсистентный OMS state

👉 ❌ STAGE 3 = NOT CLOSED

---

# 📊 FINAL VERDICT

## ✅ STAGE 3 CLOSED

Только если ВСЕ критерии выполнены:

- Risk полностью контролирует execution
- Strategy изолирована
- Execution безопасен и идемпотентен
- система устойчива к сбоям
- нет потери событий

---

## ❌ STAGE 3 NOT CLOSED

Если есть хотя бы 1 блокер:

- система возвращается в FIX PHASE
- Stage 4 ЗАПРЕЩЁН
- аудит продолжается до устранения

---

# 🔁 ПРАВИЛО ПЕРЕХОДА

## Если NOT CLOSED:

→ вернуться к аудиту  
→ исправить блокеры  
→ повторная проверка

---

## Если CLOSED:

→ фиксируется FINAL PIPELINE  
→ система считается READY для Stage 4  
→ Stage 3 больше не модифицируется

---

# 🧠 ЖЁСТКОЕ ПРАВИЛО

Stage 3 НЕ считается завершённым из-за:

- “улучшений”
- “рефакторинга качества”
- “оптимизаций”
- “архитектурных улучшений”

ONLY CRITERIA MATTER.

---

# 🚀 ФИНАЛЬНЫЙ ПРИНЦИП

👉 Система либо безопасна для денег  
👉 либо НЕ готова к Stage 4