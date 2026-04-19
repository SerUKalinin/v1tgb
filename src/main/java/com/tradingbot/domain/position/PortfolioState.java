package com.tradingbot.domain.position;

import com.tradingbot.domain.model.Position;
import lombok.Value;

import java.util.Map;

@Value
public class PortfolioState {
    Map<String, Position> positions;
    
    public Position getPosition(String symbol) {
        return positions.get(symbol);
    }
}
