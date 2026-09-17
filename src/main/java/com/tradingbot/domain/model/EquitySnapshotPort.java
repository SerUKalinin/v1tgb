package com.tradingbot.domain.model;

/**
 * Persistence boundary для equity snapshot.
 *
 * <p>
 * Application layer не должен зависеть от JPA repository/entity.
 *
 * <p>
 * Контракты:
 * SYSTEM_CONTRACT.md
 * STATE_MACHINE_CONTRACT.md
 * EXECUTION_ENGINE_CONTRACT.md
 */
public interface EquitySnapshotPort {

    void save(EquitySnapshot snapshot);
}