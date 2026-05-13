package com.tradingbot.tracing;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * SSOT IDENTITY FACTORY
 * Единственное место в системе, где разрешена генерация UUID.
 * Все ID должны быть детерминированными производными от root identity.
 */
public final class IdentityFactory {

    private IdentityFactory() {}

    /**
     * Генерирует детерминированный UUID на основе родительского ID и соли.
     * Обеспечивает правило: identity is NEVER recreated randomly.
     */
    public static UUID derive(UUID parentId, String salt) {
        if (parentId == null) throw new IllegalArgumentException("parentId cannot be null");
        String source = parentId.toString() + ":" + salt;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }

    public static UUID deriveOrder(UUID signalId) {
        return derive(signalId, "order");
    }

    public static UUID deriveExecution(UUID orderId, int attempt) {
        return derive(orderId, "execution:" + attempt);
    }

    public static UUID deriveEventId(UUID causationId, String eventType) {
        return derive(causationId, "event:" + eventType);
    }

    /**
     * Единственное исключение для создания нового root identity (Signal).
     * Должно вызываться только на внешнем входе системы.
     */
    public static UUID newRoot() {
        return UUID.randomUUID();
    }
}

