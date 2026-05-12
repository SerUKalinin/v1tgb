package com.tradingbot.tracing;

import java.util.UUID;
import java.util.Objects;

/**
 * IMMUTABLE ROOT IDENTITY
 * Идентифицирует первоначальный сигнал/агрегат.
 */
public record IdentityContext(
    UUID signalId,
    UUID correlationId
) {
    public IdentityContext {
        Objects.requireNonNull(signalId, "signalId cannot be null");
        Objects.requireNonNull(correlationId, "correlationId cannot be null");
    }

    public static IdentityContext of(UUID signalId) {
        return new IdentityContext(signalId, signalId);
    }

    public UUID aggregateId() {
        return signalId;
    }
}
