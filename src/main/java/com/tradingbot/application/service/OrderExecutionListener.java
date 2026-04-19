package com.tradingbot.application.service;

import com.tradingbot.domain.event.OrderReadyForExecutionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

@Component
@Slf4j
@RequiredArgsConstructor
public class OrderExecutionListener {

    private final OrderOrchestrator orderOrchestrator;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderReady(OrderReadyForExecutionEvent event) {
        log.info("[Listener] Dispatching order {} to orchestrator", event.orderId());
        orderOrchestrator.executeOrder(event.orderId());
    }
}