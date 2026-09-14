package com.tradingbot.infrastructure.client.fake;

import com.tradingbot.domain.model.MarketData;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Фейковая реализация клиента рыночных данных для режима backtest.
 *
 * <p>Используется исключительно в тестовых и симуляционных окружениях
 * (Spring profile: {@code backtest}).
 *
 * <p>Генерирует синтетические рыночные цены без обращения к внешним API.
 */
@Component
@Profile("backtest")
public class FakeMarketDataClient implements MarketDataClient {

    @Override
    public BigDecimal getPrice(String symbol) {
        return getMarketData(symbol).price();
    }

    /**
     * Возвращает сгенерированные рыночные данные для указанного символа.
     *
     * <p>Цена моделируется случайным отклонением вокруг базового значения ~60000
     * (условно для BTC), что позволяет имитировать рыночную волатильность
     * в backtest-режиме.
     *
     * @param symbol торговый символ (например, BTCUSDT)
     * @return сгенерированные рыночные данные {@link MarketData}
     */
    @Override
    public MarketData getMarketData(String symbol) {
        // Генерируем случайную цену в районе 60000 для BTC
        double randomPrice = 60000 + ThreadLocalRandom.current().nextDouble(-100, 100);

        return new MarketData(
                symbol,
                BigDecimal.valueOf(randomPrice),
                Instant.now()
        );
    }
}