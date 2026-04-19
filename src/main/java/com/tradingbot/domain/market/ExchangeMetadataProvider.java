package com.tradingbot.domain.market;

import java.math.BigDecimal;

public interface ExchangeMetadataProvider {
    BigDecimal getLotSize(String symbol);
    BigDecimal getMinNotional(String symbol);
    int getPricePrecision(String symbol);
    int getQuantityPrecision(String symbol);
}
