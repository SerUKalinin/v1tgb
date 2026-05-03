package com.tradingbot.infrastructure.execution.exchange;

import com.tradingbot.domain.exchange.SymbolConstraints;
import com.tradingbot.infrastructure.execution.binance.BinanceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeMetadataService {

    private final BinanceClient binanceClient;
    private final Map<String, SymbolConstraints> cache = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refreshCache();
    }

    @Scheduled(fixedRateString = "${exchange.metadata.refresh-rate:21600000}") // Default 6 hours
    public void refreshCache() {
        log.info("[METADATA] Refreshing exchange info cache...");
        try {
            Map<String, Object> exchangeInfo = binanceClient.get("/api/v3/exchangeInfo", Map.of(), Map.class, false);
            if (exchangeInfo == null || !exchangeInfo.containsKey("symbols")) {
                log.error("[METADATA] Failed to fetch exchange info: empty response");
                return;
            }

            List<Map<String, Object>> symbols = (List<Map<String, Object>>) exchangeInfo.get("symbols");
            for (Map<String, Object> symbolData : symbols) {
                parseAndCacheSymbol(symbolData);
            }
            log.info("[METADATA] Cache refreshed. Loaded {} symbols", cache.size());
        } catch (Exception e) {
            log.error("[METADATA] Error refreshing exchange info cache. Keeping old data.", e);
        }
    }

    public Optional<SymbolConstraints> getConstraints(String symbol) {
        SymbolConstraints constraints = cache.get(symbol);
        if (constraints == null) {
            log.info("[METADATA] Symbol {} not found in cache, attempting lazy load...", symbol);
            return lazyLoadSymbol(symbol);
        }
        return Optional.of(constraints);
    }

    private Optional<SymbolConstraints> lazyLoadSymbol(String symbol) {
        try {
            Map<String, Object> response = binanceClient.get("/api/v3/exchangeInfo", Map.of("symbol", symbol), Map.class, false);
            if (response != null && response.containsKey("symbols")) {
                List<Map<String, Object>> symbols = (List<Map<String, Object>>) response.get("symbols");
                if (!symbols.isEmpty()) {
                    return Optional.of(parseAndCacheSymbol(symbols.get(0)));
                }
            }
        } catch (Exception e) {
            log.error("[METADATA] Failed to lazy load symbol: {}", symbol, e);
        }
        return Optional.empty();
    }

    private SymbolConstraints parseAndCacheSymbol(Map<String, Object> symbolData) {
        String symbol = (String) symbolData.get("symbol");
        List<Map<String, Object>> filters = (List<Map<String, Object>>) symbolData.get("filters");
        
        SymbolConstraints.SymbolConstraintsBuilder builder = SymbolConstraints.builder().symbol(symbol);
        
        for (Map<String, Object> filter : filters) {
            String filterType = (String) filter.get("filterType");
            switch (filterType) {
                case "LOT_SIZE" -> {
                    builder.stepSize(new BigDecimal((String) filter.get("stepSize")));
                    builder.minQty(new BigDecimal((String) filter.get("minQty")));
                }
                case "PRICE_FILTER" -> builder.tickSize(new BigDecimal((String) filter.get("tickSize")));
                case "NOTIONAL" -> builder.minNotional(new BigDecimal((String) filter.get("minNotional")));
            }
        }
        
        SymbolConstraints constraints = builder.build();
        if (constraints.getStepSize() != null) {
            cache.put(symbol, constraints);
        }
        return constraints;
    }
}
