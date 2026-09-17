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
 *
 * <p>Отвечает за получение исторических свечей (klines) через REST API Binance
 * и преобразование сырого ответа в доменную модель {@link Candle}.
 *
 * <p>Является инфраструктурным адаптером и не содержит бизнес-логики.
 */
@Component
@RequiredArgsConstructor
public class BinanceMarketDataClient {

    private final BinanceClient binanceClient;

    /**
     * Получает свечные данные с Binance.
     *
     * <p>Параметр {@code limit} определяет количество свечей,
     * которые необходимо запросить.
     *
     * <p>Признак {@code isClosed} определяется на основании
     * серверного времени Binance и closeTime конкретной свечи.
     *
     * @param symbol   торговый символ (например BTCUSDT)
     * @param interval свечной интервал (например 1m, 5m, 1h)
     * @param limit    количество свечей для загрузки
     * @return список свечей; пустой список, если данных нет
     */
    public List<Candle> getCandles(
            String symbol,
            String interval,
            int limit
    ) {
        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "limit должен быть положительным: " + limit
            );
        }

        Map<String, String> params = Map.of(
                "symbol", symbol,
                "interval", interval,
                "limit", String.valueOf(limit)
        );

        Object[][] response = binanceClient.get(
                "/api/v3/klines",
                params,
                Object[][].class,
                false
        );

        if (response == null || response.length == 0) {
            return List.of();
        }

        Instant serverNow = Instant.ofEpochMilli(
                binanceClient.getServerTime()
        );

        return Arrays.stream(response)
                .map(data -> mapToCandle(symbol, data, serverNow))
                .toList();
    }

    /**
     * Преобразует массив данных свечи, полученный от Binance API,
     * в доменную модель {@link Candle}.
     *
     * <p>Формат массива соответствует Binance Klines API:
     * <ul>
     *   <li>[0] - open time</li>
     *   <li>[1] - open price</li>
     *   <li>[2] - high price</li>
     *   <li>[3] - low price</li>
     *   <li>[4] - close price</li>
     *   <li>[5] - volume</li>
     *   <li>[6] - close time</li>
     * </ul>
     *
     * @param symbol    торговый символ
     * @param data      массив данных одной свечи
     * @param serverNow текущее время Binance
     * @return доменная свеча {@link Candle}
     */
    private Candle mapToCandle(
            String symbol,
            Object[] data,
            Instant serverNow
    ) {
        if (data == null || data.length < 7) {
            throw new IllegalArgumentException(
                    "Некорректный Binance kline: недостаточно полей"
            );
        }

        Instant openTime = Instant.ofEpochMilli(
                ((Number) data[0]).longValue()
        );

        Instant closeTime = Instant.ofEpochMilli(
                ((Number) data[6]).longValue()
        );

        boolean isClosed = !closeTime.isAfter(serverNow);

        return Candle.builder()
                .symbol(symbol)
                .openTime(openTime)
                .open(new BigDecimal(data[1].toString()))
                .high(new BigDecimal(data[2].toString()))
                .low(new BigDecimal(data[3].toString()))
                .close(new BigDecimal(data[4].toString()))
                .volume(new BigDecimal(data[5].toString()))
                .closeTime(closeTime)
                .isClosed(isClosed)
                .build();
    }
}