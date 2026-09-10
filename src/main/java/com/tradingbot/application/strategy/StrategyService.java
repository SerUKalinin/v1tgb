package com.tradingbot.application.strategy;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.event.NewClosedCandleEvent;
import com.tradingbot.domain.event.SignalEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Сервис обработки стратегических событий.
 *
 * <p>Является оркестратором между:
 * <ul>
 *     <li>потоком рыночных данных (свечи)</li>
 *     <li>движком стратегии</li>
 *     <li>event-driven pipeline системы</li>
 * </ul>
 *
 * <p>Основная задача — преобразование рыночных событий в торговые сигналы
 * и публикация их в систему событий.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyService {

    /**
     * Движок торговой стратегии.
     */
    private final StrategyEngine strategyEngine;

    /**
     * Паблишер доменных событий Spring.
     */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Менеджер состояния системы (контроль готовности к торговле).
     */
    private final SystemStateManager stateManager;

    /**
     * Обработчик закрытой свечи.
     *
     * <p>Запускает стратегический анализ только если система находится
     * в состоянии READY.</p>
     *
     * @param event событие закрытой свечи
     */
    @EventListener
    public void onNewCandle(NewClosedCandleEvent event) {

        if (!stateManager.isReady()) {
            return;
        }

        Optional<SignalEvent> signal = strategyEngine.evaluate(event);

        signal.ifPresent(s -> {
            log.info("[STRATEGY] SIGNAL generated {} {}", s.getSymbol(), s.getType());
            eventPublisher.publishEvent(s);
        });
    }
}