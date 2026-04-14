# План реализации Stage 1: Данные и интеграция с Binance

## 1. Доменные модели и интерфейсы
- [ ] Создать `Candle.java` (модель свечи)
- [ ] Создать `CandleWindow.java` (окно свечей для анализа)
- [ ] Обновить `MarketDataClient.java` (добавить методы для получения свечей)

## 2. Инфраструктура: Binance Client
- [ ] Улучшить `BinanceClient.java`:
    - [ ] Реализовать подпись HMAC SHA256
    - [ ] Синхронизация времени с сервером
    - [ ] Механизм повторных попыток (Retry)
- [ ] Реализовать `BinanceMarketDataClient.java` (интеграция с Klines API)

## 3. Прикладной уровень: Управление данными
- [ ] Создать `MarketDataCache.java` (кэширование последних свечей)
- [ ] Создать `MarketDataService.java` (бизнес-логика получения и подготовки данных)

## 4. Рефакторинг и пайплайн
- [ ] Переименовать/обновить `TradingPipelineService` в `TradingPipeline`
- [ ] Обновить `TradingPipeline` для работы с `CandleWindow`
- [ ] Обновить `TradingStrategy` интерфейс для приема `CandleWindow`

## 5. Конфигурация и запуск
- [ ] Добавить `@EnableScheduling` в `TradingBotApplication.java`
- [ ] Исправить конфликты бинов в `AppConfig.java`
- [ ] Настроить параметры Binance в `application.yaml`

## 6. Исполнение ордеров и идемпотентность
- [ ] Добавить `clientOrderId` в `OrderRequest`
- [ ] Обновить `BinanceExecutionEngine` для поддержки идемпотентности
