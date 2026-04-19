package com.tradingbot.application.pipeline;

import com.tradingbot.application.service.OrderManagementService;
import com.tradingbot.domain.model.Signal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class SignalEventListener {

    private final OrderManagementService orderManagementService;

    @EventListener
    public void onSignal(Signal signal) {
        log.info("[PIPELINE] Received signal for {}", signal.getSymbol());
        orderManagementService.processSignal(signal);
    }
}

