package com.tradingbot.domain.risk;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port для восстановления RiskState из persistence.
 *
 * Application слой работает только с domain-типами и
 * recovery DTO, не зная о JPA repository/entity/mapper.
 */
public interface RiskStateRecoveryPort {

    /**
     * Загружает последний snapshot состояния риска.
     *
     * @param aggregateId идентификатор aggregate
     * @return JSON snapshot либо empty
     */
    Optional<String> findLatestSnapshotStateJson(
            String aggregateId
    );

    /**
     * Загружает сохранённое состояние риска.
     *
     * @param aggregateId идентификатор aggregate
     * @return domain RiskState либо empty
     */
    Optional<RiskState> findPersistedState(
            String aggregateId
    );

    /**
     * Загружает risk events после указанной версии.
     *
     * @param aggregateId aggregate id
     * @param version     последняя восстановленная версия
     */
    List<RiskEventRecord> findEventsAfter(
            String aggregateId,
            Long version
    );

    /**
     * Загружает полный reservation log
     * в deterministic sequence order.
     */
    List<RiskReservationRecord> findAllReservations();

    /**
     * Чистое представление persisted risk event.
     */
    record RiskEventRecord(
            String eventType,
            String payload
    ) {
    }

    /**
     * Чистое представление reservation log entry.
     */
    record RiskReservationRecord(
            UUID orderId,
            String eventType,
            BigDecimal amount
    ) {
    }
}