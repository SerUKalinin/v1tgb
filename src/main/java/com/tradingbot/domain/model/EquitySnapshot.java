package com.tradingbot.domain.model;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Доменная модель снимка equity.
 *
 * <p>
 * Не содержит JPA/persistence-зависимостей.
 *
 * <p>
 * Контракты:
 * SYSTEM_CONTRACT.md
 * STATE_MACHINE_CONTRACT.md
 * EXECUTION_ENGINE_CONTRACT.md
 */
@Value
@Builder
public class EquitySnapshot {

    String strategyId;

    Instant timestamp;

    BigDecimal balance;

    BigDecimal unrealizedPnl;

    BigDecimal equity;
}