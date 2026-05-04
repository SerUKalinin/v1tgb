package com.tradingbot.fakes;

import com.tradingbot.application.market.MarketDataService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Primary
public class FakeMarketDataService extends MarketDataService {
    private final Map<String, BigDecimal> prices = new ConcurrentHashMap<>();

    public FakeMarketDataService() {
        super(null, null, null, null, null);
    }
}
