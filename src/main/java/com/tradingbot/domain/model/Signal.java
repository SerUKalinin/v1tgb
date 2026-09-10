package com.tradingbot.domain.model;

import com.tradingbot.common.enums.SignalType;
import lombok.Value;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Торговый сигнал, генерируемый стратегическим модулем.
 * <p>
 * Является входной точкой для execution pipeline и содержит
 * минимально необходимый набор данных для принятия торгового решения.
 */
@Value
public class Signal {

    UUID signalId;
    String symbol;
    String strategyId;
    SignalType type;
    BigDecimal price;
    BigDecimal quantity;

    /**
     * Создаёт торговый сигнал с обязательной валидацией полей.
     *
     * @param signalId   идентификатор сигнала
     * @param symbol     торговый символ
     * @param strategyId идентификатор стратегии
     * @param type       тип сигнала (BUY/SELL и т.д.)
     * @param price      цена
     * @param quantity   объём
     */
    public Signal(UUID signalId,
                  String symbol,
                  String strategyId,
                  SignalType type,
                  BigDecimal price,
                  BigDecimal quantity) {

        this.signalId = Objects.requireNonNull(signalId, "signalId must not be null");
        this.symbol = Objects.requireNonNull(symbol, "symbol must not be null");
        this.strategyId = Objects.requireNonNull(strategyId, "strategyId must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.price = Objects.requireNonNull(price, "price must not be null");
        this.quantity = Objects.requireNonNull(quantity, "quantity must not be null");
    }
}