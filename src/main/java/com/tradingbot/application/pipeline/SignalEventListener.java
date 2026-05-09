package com.tradingbot.application.pipeline;

import com.tradingbot.application.service.execution.SignalExecutionFacade;
import com.tradingbot.application.service.strategy.SignalRouter;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.execution.AlreadyClaimedException;
import com.tradingbot.tracing.ExecutionLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SignalEventListener {

    private final SignalExecutionFacade signalExecutionFacade;
    private final SystemStateManager stateManager;

    @EventListener
    public void onSignal(SignalEvent signal) {

        log.info("[TRACE_FLOW] ENTER SignalEventListener.onSignal for {}", signal.getSignalId());

        if (!stateManager.isReady()) {
            log.warn("[TRACE_FLOW] EXIT SignalEventListener - System not ready. State: {}", stateManager.getState());
            return;
        }

        try {
            signalExecutionFacade.execute(signal);

            log.info("[TRACE_FLOW] EXIT SignalEventListener.onSignal - Success");

        } catch (AlreadyClaimedException e) {
            log.warn("[TRACE_FLOW] IDEMPOTENT_SKIP: Signal {} already claimed", signal.getSignalId());
            log.info("[TRACE_FLOW] EXIT SignalEventListener.onSignal - Idempotent skip");

        } catch (Exception e) {
            log.error("[TRACE_FLOW] EXIT SignalEventListener.onSignal - FAILED", e);
        }
    }
}
