package com.tradingbot.domain.exchange;

import lombok.Value;
import java.math.BigDecimal;

@Value
public class NormalizedOrder {
    String symbol;
    BigDecimal quantity;
    BigDecimal price;
}
