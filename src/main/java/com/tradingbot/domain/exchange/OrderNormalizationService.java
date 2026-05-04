package com.tradingbot.domain.exchange;

public interface OrderNormalizationService {
    NormalizedOrder normalize(FeasibilityRequest request);
}
