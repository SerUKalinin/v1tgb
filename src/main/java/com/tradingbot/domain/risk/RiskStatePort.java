package com.tradingbot.domain.risk;

import java.util.UUID;

public interface RiskStatePort {
    RiskState get();
    void save(RiskState state);
    void markEventProcessed(UUID id, RiskState state, RiskEvent event);
    boolean isEventProcessed(UUID id);
}
