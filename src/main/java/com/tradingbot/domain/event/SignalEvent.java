package com.tradingbot.domain.event;

import com.tradingbot.common.enums.SignalType;
import lombok.Builder;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor(force = true)
public class SignalEvent {
    @Builder.Default
    private final String signalId = UUID.randomUUID().toString();
    private String symbol;
    private SignalType type;
    private BigDecimal price;
    private BigDecimal quantity;
    private BigDecimal stopLoss;
    private BigDecimal takeProfit;
    private Instant candleTime;
    private String strategyId;

    public String getSymbol() {
        return symbol;
    }

    public SignalType getType() {
        return type;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getStopLoss() {
        return stopLoss;
    }

    public BigDecimal getTakeProfit() {
        return takeProfit;
    }

    public Instant getCandleTime() {
        return candleTime;
    }

    public String getStrategyId() {
        return strategyId;
    }
}


