package com.tradingbot.domain.model;

import com.tradingbot.common.enums.SignalType;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class TradingSignal {
    private SignalType type;
    private String symbol;
    private BigDecimal price;
    private BigDecimal stopLoss; // добавлено для risk
}