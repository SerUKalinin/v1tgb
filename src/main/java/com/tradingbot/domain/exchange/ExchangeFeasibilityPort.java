package com.tradingbot.domain.exchange;

public interface ExchangeFeasibilityPort {
    FeasibilityResult check(FeasibilityRequest request);
}
