package com.tradingbot.tracing;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * SSOT IDENTITY FACTORY
 *
 * Единственное место в системе, где разрешена генерация UUID.
 * Все ID должны быть детерминированными производными от root identity.
 */
public final class IdentityFactory {

    private IdentityFactory() {}

    /**
     * Генерирует детерминированный UUID
     * на основе parentId + salt.
     */
    public static UUID derive(
            UUID parentId,
            String salt
    ) {

        if (parentId == null) {
            throw new IllegalArgumentException(
                    "parentId cannot be null"
            );
        }

        if (salt == null || salt.isBlank()) {
            throw new IllegalArgumentException(
                    "salt cannot be null or blank"
            );
        }

        String source =
                parentId.toString()
                        + ":"
                        + salt;

        return UUID.nameUUIDFromBytes(
                source.getBytes(
                        StandardCharsets.UTF_8
                )
        );
    }

    public static UUID deriveOrder(
            UUID signalId
    ) {
        return derive(
                signalId,
                "order"
        );
    }

    public static UUID deriveExecution(
            UUID orderId,
            int attempt
    ) {
        return derive(
                orderId,
                "execution:" + attempt
        );
    }

    public static UUID deriveEventId(
            UUID causationId,
            String eventType
    ) {
        return derive(
                causationId,
                "event:" + eventType
        );
    }

    /**
     * Создаёт deterministic event type
     * для нового cumulative checkpoint
     * внутри того же execution lifecycle.
     *
     * executionId НЕ меняется.
     *
     * Уникальность checkpoint обеспечивается
     * deterministic checkpoint identity.
     *
     * Пример:
     *
     * TRADE_UPDATED:4e9c...
     *
     * После этого обычное правило:
     *
     * eventId =
     * deriveEventId(executionId, eventType)
     *
     * остаётся неизменным.
     */
    public static String deriveCheckpointEventType(
            UUID executionId,
            String baseEventType,
            String checkpointKey
    ) {

        if (executionId == null) {
            throw new IllegalArgumentException(
                    "executionId cannot be null"
            );
        }

        if (baseEventType == null
                || baseEventType.isBlank()) {

            throw new IllegalArgumentException(
                    "baseEventType cannot be null or blank"
            );
        }

        if (checkpointKey == null
                || checkpointKey.isBlank()) {

            throw new IllegalArgumentException(
                    "checkpointKey cannot be null or blank"
            );
        }

        UUID checkpointIdentity =
                derive(
                        executionId,
                        "checkpoint:"
                                + baseEventType
                                + ":"
                                + checkpointKey
                );

        return baseEventType
                + ":"
                + checkpointIdentity;
    }

    /**
     * Единственное исключение
     * для создания нового root identity.
     */
    public static UUID newRoot() {
        return UUID.randomUUID();
    }
}