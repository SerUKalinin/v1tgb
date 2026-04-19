package com.tradingbot.infrastructure.debug;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.domain.model.Signal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class SignalGenerator {

    public static Signal generate(String symbol, OrderSide side) {
        return Signal.builder()
                .clientOrderId(UUID.randomUUID().toString()) // вместо signalId
                .symbol(symbol)
                .side(side)
                .price(BigDecimal.valueOf(50000))
                .quantity(BigDecimal.valueOf(0.001)) // обязательно, иначе Risk может упасть
                .strategyId("debug-strategy")
                .generatedAt(Instant.now())
                .build();
    }
}