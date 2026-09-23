package com.tradingbot.application.pipeline;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.service.execution.SignalExecutionFacade;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.execution.AlreadyClaimedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Обработчик доменных событий сигналов торговли.
 *
 * <p>Является входной точкой execution pipeline.</p>
 *
 * <p>Критически важно:
 * исключения execution pipeline НЕ должны проглатываться,
 * иначе market-data слой не сможет отличить успешную обработку
 * candle от неуспешной.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignalEventListener {

    private final SignalExecutionFacade signalExecutionFacade;
    private final SystemStateManager stateManager;

    /**
     * Основной обработчик торгового сигнала.
     *
     * @param signal торговый доменный сигнал
     */
    @EventListener
    public void onSignal(
            SignalEvent signal
    ) {

        log.info(
                "[TRACE_FLOW] ENTER SignalEventListener.onSignal for {}",
                signal.getSignalId()
        );

        if (!stateManager.isReady()) {

            log.warn(
                    "[TRACE_FLOW] EXIT SignalEventListener - System not ready. State: {}",
                    stateManager.getState()
            );

            return;
        }

        try {

            signalExecutionFacade.execute(
                    signal
            );

            log.info(
                    "[TRACE_FLOW] EXIT SignalEventListener.onSignal - Success"
            );

        } catch (AlreadyClaimedException e) {

            log.warn(
                    "[TRACE_FLOW] IDEMPOTENT_SKIP: Signal {} already claimed",
                    signal.getSignalId()
            );

            log.info(
                    "[TRACE_FLOW] EXIT SignalEventListener.onSignal - Idempotent skip"
            );

            /*
             * Уже существующая семантика idempotent skip.
             *
             * Такой сигнал считается обработанным.
             */
            return;

        } catch (Exception e) {

            log.error(
                    "[TRACE_FLOW] EXIT SignalEventListener.onSignal - FAILED",
                    e
            );

            /*
             * НИКАКОГО silent swallow.
             *
             * Исключение должно выйти обратно в
             * MarketDataService.publishEvent(),
             * чтобы Candle ACK не выполнялся.
             */
            throw e;
        }
    }
}