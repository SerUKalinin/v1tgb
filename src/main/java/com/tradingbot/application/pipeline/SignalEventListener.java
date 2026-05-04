package com.tradingbot.application.pipeline;

import com.tradingbot.application.service.order.OrderApplicationService;
import com.tradingbot.application.service.strategy.SignalRouter;
import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.domain.event.SignalEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SignalEventListener {

    private final OrderApplicationService orderApplicationService;
    private final SystemStateManager stateManager;
    private final SignalRouter signalRouter;

    @EventListener
    public void onSignal(SignalEvent signal) {
        if (!stateManager.isReady()) {
            log.warn("[SIGNAL] System not ready. Ignoring signal for {}", signal.getSymbol());
            return;
        }

        log.info("[SIGNAL] Received signal event for symbol: {}", signal.getSymbol());        
        
        // 1. Маршрутизация (уведомления и т.д.)
        signalRouter.route(signal);

        // 2. Обработка сигнала
        try {
            orderApplicationService.onSignalReceived(signal);
        } catch (Exception e) {
            log.error("[SIGNAL] Error processing signal for {}: {}", 
                    signal.getSymbol(), e.getMessage());
        }
    }
}
