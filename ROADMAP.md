# Roadmap: Telegram Trading SaaS MVP

## Архитектура
- **CORE**: Чистый движок (Strategy, Risk, Pipeline). Генерирует `SignalEvent`.
- **DOMAIN**: Бизнес-модели (User, Subscription, Signal).
- **APPLICATION**: Мозг (SignalRouter, SubscriptionService, EventListeners).
- **INFRASTRUCTURE**: Провода (Telegram, Persistence, API Clients).

---

## Статус реализации

### 🟢 ШАГ 1: БАЗА (ЗАВЕРШЕНО)
- [x] Добавить зависимости (Telegram Bot Starter)
- [x] Создать Domain модели (`User`, `SubscriptionLevel`)
- [x] Реализовать `TelegramBot` (базовый конфиг + `/start`)
- [x] Инфраструктура для хранения пользователей (In-memory MVP)

### 🟡 ШАГ 2: СВЯЗКА С CORE (ЗАВЕРШЕНО)
- [x] Интеграция `ApplicationEventPublisher` в `TradingPipeline`
- [x] Создать `SignalEventListener` в слое Application
- [x] Реализовать базовый `SignalRouter`

### 🔵 ШАГ 3: МОНЕТИЗАЦИЯ (ЗАВЕРШЕНО)
- [x] `SubscriptionService` (логика проверки доступа)
- [x] Фильтрация сигналов (FREE/PRO логика в `SignalRouter`)
- [x] Команда `/status` и ручная активация PRO

---

## 🎉 MVP ГОТОВ К ЗАПУСКУ!
Бот готов рассылать сигналы и разделять пользователей по уровням доступа.

---

## Правила разработки
1. **CORE** не знает о Telegram и User.
2. **DOMAIN** — чистые POJO.
3. **INFRASTRUCTURE** — только реализация интерфейсов и внешние вызовы.
4. **APPLICATION** — координация и бизнес-правила.
