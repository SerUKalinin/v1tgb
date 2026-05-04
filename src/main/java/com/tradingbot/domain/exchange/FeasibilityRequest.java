package com.tradingbot.domain.exchange;

import lombok.Value;
import java.math.BigDecimal;

@Value
public class FeasibilityRequest {
    String symbol;
    BigDecimal quantity;
    BigDecimal price;
}
