package com.tradingbot.application.service.strategy;

import com.tradingbot.application.event.NewClosedCandleEvent;
import com.tradingbot.application.strategy.StrategyEngine;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.tracing.IdentityFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Заглушечная стратегия SMA Cross (упрощённая модель генерации сигналов).
 *
 * <p>Используется как тестовый/демонстрационный StrategyEngine,
 * генерирующий сигналы без реального анализа скользящих средних.</p>
 *
 * <p>Поведение:
 * <ul>
 *     <li>BUY — если close > open</li>
 *     <li>SELL — если close <= open</li>
 * </ul>
 *
 * <p>Не предназначена для production-торговли.</p>
 */
@Primary
@Component
public class SmaCrossStubStrategyEngine implements StrategyEngine {

    /**
     * Выполняет оценку закрытой свечи и генерирует торговый сигнал.
     *
     * <p>Алгоритм упрощён до направления свечи:
     * рост → BUY, падение → SELL.</p>
     *
     * @param candle закрытая свеча
     * @return Optional сигнала стратегии
     */
    @Override
    public Optional<SignalEvent> evaluate(NewClosedCandleEvent candle) {

        SignalType type = candle.close().compareTo(candle.open()) > 0
                ? SignalType.BUY
                : SignalType.SELL;

        UUID signalId = IdentityFactory.derive(
                UUID.nameUUIDFromBytes(candle.symbol().getBytes()),
                "sma-" + candle.closeTime()
        );

        SignalEvent signal = new SignalEvent(
                signalId,
                candle.symbol(),
                type,
                candle.close(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                candle.closeTime(),
                "SMA_STUB"
        );

        return Optional.of(signal);
    }
}