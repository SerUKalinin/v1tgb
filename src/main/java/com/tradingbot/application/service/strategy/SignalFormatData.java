package com.tradingbot.application.service.strategy;

import com.tradingbot.common.enums.SignalType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Application DTO с данными сигнала,
 * необходимыми только для форматирования сообщения.
 *
 * <p>
 * Не содержит persistence entity и JPA-зависимостей.
 *
 * <p>
 * Архитектурные контракты:
 * SYSTEM_CONTRACT.md
 * STATE_MACHINE_CONTRACT.md
 * EXECUTION_ENGINE_CONTRACT.md
 */
public record SignalFormatData(
        String symbol,
        SignalType type,
        BigDecimal price,
        BigDecimal takeProfit1,
        BigDecimal takeProfit2,
        BigDecimal stopLoss,
        Instant timestamp
) {
}