package com.tradingbot.application.service;

import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.event.SignalEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderManagementService {

    private final OrderApplicationService orderApplicationService;

    @EventListener
    public void onSignal(SignalEvent event) {

        if (event.getType() == SignalType.HOLD) {
            return;
        }

        log.info(
                "[OMS] routing signal {} {}",
                event.getSymbol(),
                event.getType()
        );

        orderApplicationService.placeOrder(event);
    }
}