package com.tradingbot.application.strategy;

import com.tradingbot.application.event.NewClosedCandleEvent;
import com.tradingbot.domain.event.SignalEvent;

import java.util.Optional;

/**
 * Интерфейс стратегии торгового движка.
 *
 * <p>Определяет контракт для всех торговых стратегий системы,
 * которые анализируют закрытую свечу и могут сформировать торговый сигнал.</p>
 *
 * <p>Реализации могут варьироваться от простых эвристик до сложных
 * алгоритмических моделей.</p>
 */
public interface StrategyEngine {

    /**
     * Выполняет анализ закрытой свечи и генерирует торговый сигнал (если есть).
     *
     * <p>Если стратегия не обнаружила торговую возможность,
     * возвращается {@link Optional#empty()}.</p>
     *
     * @param candle событие закрытой свечи
     * @return optional торгового сигнала
     */
    Optional<SignalEvent> evaluate(NewClosedCandleEvent candle);
}