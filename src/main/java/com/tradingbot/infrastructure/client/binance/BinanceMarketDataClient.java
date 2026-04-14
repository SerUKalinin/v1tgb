package com.tradingbot.infrastructure.client.binance;

import com.tradingbot.domain.model.Candle;
import com.tradingbot.infrastructure.execution.binance.BinanceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Клиент для получения рыночных данных с Binance.
 */
@Component
@RequiredArgsConstructor
public class BinanceMarketDataClient {

    private final BinanceClient binanceClient;

    /**
     * Получает свечные данные с Binance.
     *
     * @param symbol   торговый символ
     * @param interval свечной интервал
     * @param limit    количество свечей
     * @return список свечей
     */
    public List<Candle> getCandles(String symbol, String interval, int limit) {
        Map<String, String> params = Map.of(
                "symbol", symbol,
                "interval", interval,
                "limit", String.valueOf(limit)
        );

        Object[][] response = binanceClient.get("/api/v3/klines", params, Object[][].class, false);

        if (response == null) {
            return List.of();
        }

        return Arrays.stream(response)
                .map(data -> mapToCandle(symbol, data))
                .toList();
    }

    /**
     * Преобразует данные из ответа Binance в модель Candle.
     *
     * @param symbol торговый символ
     * @param data   массив данных свечи
     * @return объект Candle
     */
    private Candle mapToCandle(String symbol, Object[] data) {
        return Candle.builder()
                .symbol(symbol)
                .openTime(Instant.ofEpochMilli(((Number) data[0]).longValue()))
                .open(new BigDecimal(data[1].toString()))
                .high(new BigDecimal(data[2].toString()))
                .low(new BigDecimal(data[3].toString()))
                .close(new BigDecimal(data[4].toString()))
                .volume(new BigDecimal(data[5].toString()))
                .closeTime(Instant.ofEpochMilli(((Number) data[6]).longValue()))
                .isClosed(true)
                .build();
    }
}