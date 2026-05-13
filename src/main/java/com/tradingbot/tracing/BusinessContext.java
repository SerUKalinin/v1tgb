package com.tradingbot.tracing;

import java.util.Objects;
import java.util.Map;
import java.util.Collections;

/**
 * DOMAIN STATE
 * Содержит только бизнес-данные, необходимые для выполнения операции.
 */
public record BusinessContext(
    String orderId,
    Map<String, Object> params
) {
    public BusinessContext {
        Objects.requireNonNull(orderId, "orderId cannot be null");
        params = params != null ? Map.copyOf(params) : Collections.emptyMap();
    }

    public static BusinessContext empty() {
        return new BusinessContext("NONE", Collections.emptyMap());
    }

    public static BusinessContext of(String orderId) {
        return new BusinessContext(orderId, Collections.emptyMap());
    }

    public static BusinessContext of(String orderId, Map<String, Object> params) {
        return new BusinessContext(orderId, params);
    }
}
