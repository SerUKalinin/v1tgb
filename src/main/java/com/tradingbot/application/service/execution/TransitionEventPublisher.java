package com.tradingbot.application.service.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Публикатор доменных событий перехода состояния ордера с учётом транзакционной границы.
 *
 * <p>Обеспечивает корректную публикацию TransitionEvent:
 * <ul>
 *     <li>после успешного commit транзакции (afterCommit)</li>
 *     <li>либо немедленно при отсутствии активной транзакции</li>
 * </ul>
 *
 * <p>Используется для гарантии, что внешние обработчики событий
 * видят только консистентное состояние системы.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransitionEventPublisher {

    private final ApplicationEventPublisher eventPublisher;

    /**
     * Публикует событие перехода состояния ордера.
     *
     * <p>Если активна транзакция Spring, событие откладывается до afterCommit.
     * Иначе публикуется немедленно.</p>
     *
     * @param event событие перехода состояния ордера
     */
    public void publishAfterCommit(TransitionEvent event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    log.debug(
                            "[EVENT] Publishing transition event after commit for order {}: {} -> {}",
                            event.getOrderId(),
                            event.getFromStatus(),
                            event.getToStatus()
                    );
                    eventPublisher.publishEvent(event);
                }
            });
        } else {
            log.debug("[EVENT] No active transaction, publishing immediately for order {}", event.getOrderId());
            eventPublisher.publishEvent(event);
        }
    }
}