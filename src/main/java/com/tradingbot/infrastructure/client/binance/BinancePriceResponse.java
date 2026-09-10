package com.tradingbot.infrastructure.client.binance;

import lombok.Data;

/**
 * DTO-ответ Binance API с текущей ценой торгового символа.
 *
 * <p>Используется как транспортная модель для десериализации ответа
 * эндпоинта Binance, возвращающего актуальную рыночную цену.
 *
 * <p>Является инфраструктурным DTO и не содержит бизнес-логики.
 */
@Data
public class BinancePriceResponse {

    /**
     * Торговый символ (например, BTCUSDT).
     */
    private String symbol;

    /**
     * Текущая рыночная цена в виде строки (сырой формат Binance API).
     *
     * <p>Требует преобразования в {@link java.math.BigDecimal} на уровне сервиса
     * перед использованием в бизнес-логике.
     */
    private String price;
}