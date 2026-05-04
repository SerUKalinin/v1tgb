package com.tradingbot.application.service.execution;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransitionDispatcher {

    private final StateTransitionExecutor executor;
    private final TransitionEventPublisher eventPublisher;

    /**
     * Выполняет переход и гарантирует публикацию события строго после коммита.
     */
    @Transactional
    public TransitionEvent dispatch(com.tradingbot.domain.model.Order order, 
                                   com.tradingbot.infrastructure.persistence.entity.OrderEntity entity, 
                                   com.tradingbot.common.enums.OrderStatus targetStatus, 
                                   Runnable domainAction) {
        
        TransitionEvent event = executor.execute(order, entity, targetStatus, domainAction);
        
        // Регистрируем публикацию в TransactionSynchronizationManager через издателя
        eventPublisher.publishAfterCommit(event);
        
        return event;
    }
}
