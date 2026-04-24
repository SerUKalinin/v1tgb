package com.tradingbot.application.pipeline;

import com.tradingbot.application.service.OrderApplicationService;
import com.tradingbot.application.service.SignalRouter;
import com.tradingbot.domain.event.SignalEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@org.springframework.context.annotation.Profile("!test")
public class SignalEventListener {

    private final SignalRouter signalRouter;
    private final OrderApplicationService orderApplicationService;

    @EventListener
    public void onSignal(SignalEvent event) {
        log.info("[EVENT-LISTENER] Received signal event for symbol: {}", event.getSymbol());
        
        // 1. Уведомления и логирование (Side effects)
        signalRouter.route(event);
        
        // 2. Делегирование в OrderApplicationService для исполнения (Core logic)
        try {
            orderApplicationService.onSignalReceived(event);
        } catch (Exception e) {
            log.error("[EVENT-LISTENER] Failed to process signal for symbol {}: {}", 
                    event.getSymbol(), e.getMessage());
        }
    }
}
