package com.tradingbot.application.service.execution;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.policy.TransitionValidator;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.adapter.StatePersistenceAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StateTransitionExecutor {

    private final StatePersistenceAdapter persistenceAdapter;
    private final TransitionValidator transitionValidator;

    public TransitionContext prepare(Order order, OrderEntity entity, OrderStatus target) {
        return TransitionContext.create(order, entity, target);
    }

    public TransitionContext validate(TransitionContext context) {
        OrderStatus validatedStatus = transitionValidator.validate(
                context.getInitialStatus(),
                context.getTargetStatus()
        );
        return context.withValidatedStatus(validatedStatus);
    }

    public void apply(TransitionContext context, Runnable domainAction) {
        if (domainAction != null) {
            domainAction.run();
        }
        // Единственное место мутации домена в приложении
        context.getOrderReference().updateStatus(context.getTargetStatus());
    }

    @Transactional
    public void persist(TransitionContext context) {
        persistenceAdapter.syncAndSave(context.getOrderReference(), context.getEntityReference());
    }

    @Transactional
    public TransitionEvent execute(Order order, OrderEntity entity, OrderStatus targetStatus, Runnable domainAction) {
        TransitionContext context = prepare(order, entity, targetStatus);
        context = validate(context);
        apply(context, domainAction);
        persist(context);

        return TransitionEvent.fromContext(context);
    }
}
