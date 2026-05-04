package com.tradingbot.application.service.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransitionEventPublisher {
    private final ApplicationEventPublisher eventPublisher;

    public void publishAfterCommit(TransitionEvent event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    log.debug("[EVENT] Publishing transition event after commit for order {}: {} -> {}", 
                        event.getOrderId(), event.getFromStatus(), event.getToStatus());
                    eventPublisher.publishEvent(event);
                }
            });
        } else {
            log.debug("[EVENT] No active transaction, publishing immediately for order {}", event.getOrderId());
            eventPublisher.publishEvent(event);
        }
    }
}
