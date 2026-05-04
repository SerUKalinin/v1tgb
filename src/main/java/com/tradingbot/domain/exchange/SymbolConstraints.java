package com.tradingbot.domain.exchange;

import lombok.Builder;
import lombok.Value;
import java.math.BigDecimal;

@Value
@Builder
public class SymbolConstraints {
    String symbol;
    BigDecimal stepSize;    // LOT_SIZE: stepSize
    BigDecimal minQty;      // LOT_SIZE: minQty
    BigDecimal tickSize;    // PRICE_FILTER: tickSize
    BigDecimal minNotional; // NOTIONAL: minNotional
}
