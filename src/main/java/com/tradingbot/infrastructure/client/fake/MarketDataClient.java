package com.tradingbot.infrastructure.client.fake;

import com.tradingbot.domain.model.MarketData;
import java.math.BigDecimal;

/**
 * Клиент для получения рыночных данных.
 *
 * <p>Интерфейс абстрагирует источник котировок (реальный биржевой API или
 * тестовые/симуляционные реализации). Используется для изоляции доменной
 * логики от инфраструктуры получения цен.
 */
public interface MarketDataClient {

    /**
     * Возвращает текущую цену указанного торгового символа.
     *
     * @param symbol торговый символ (например, BTCUSDT)
     * @return текущая рыночная цена
     */
    BigDecimal getPrice(String symbol);

    /**
     * Возвращает полные рыночные данные по указанному символу.
     *
     * @param symbol торговый символ (например, BTCUSDT)
     * @return объект рыночных данных {@link MarketData}
     */
    MarketData getMarketData(String symbol);
}