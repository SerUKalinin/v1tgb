package com.tradingbot.domain.model;

import java.math.BigDecimal;

/**
 * Порт полной реконструкции позиций.
 *
 * <p>Application layer не должен зависеть от JPA Repository или Entity.
 *
 * <p>Контракты:
 * SYSTEM_CONTRACT.md
 * STATE_MACHINE_CONTRACT.md
 * EXECUTION_ENGINE_CONTRACT.md
 */
public interface PositionRebuildPort {

    /**
     * Полностью удаляет текущую projection позиций.
     */
    void clear();

    /**
     * Сохраняет восстановленную позицию.
     *
     * @param position восстановленная доменная позиция
     * @param realizedPnl рассчитанный реализованный PnL
     */
    void save(Position position, BigDecimal realizedPnl);
}