package com.tradingbot.infrastructure.execution.binance;

import com.tradingbot.domain.market.ExchangeMetadataProvider;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

@Component
public class BinanceMetadataProvider implements ExchangeMetadataProvider {
    @Override
    public BigDecimal getLotSize(String symbol) {
        return new BigDecimal("0.00001"); // Mock
    }

    @Override
    public BigDecimal getMinNotional(String symbol) {
        return new BigDecimal("10.0"); // Mock
    }

    @Override
    public int getPricePrecision(String symbol) {
        return 2;
    }

    @Override
    public int getQuantityPrecision(String symbol) {
        return 5;
    }
}
