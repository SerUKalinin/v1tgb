package com.tradingbot.domain.model;

import com.tradingbot.common.enums.SignalType;
import lombok.Value;

import java.math.BigDecimal;

@Value
public class Signal {
    String symbol;
    SignalType type;
    BigDecimal price;
}