package com.tradingbot.application.service.execution;

import com.tradingbot.application.service.order.OrderApplicationService;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.execution.ExecutionClaimPort;
import com.tradingbot.tracing.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class SignalExecutionFacade {

    private final ExecutionClaimPort executionClaimPort;
    private final OrderApplicationService orderApplicationService;

    @Transactional
    public void execute(SignalEvent signal) {

        IdentityContext identity = signal.getIdentity();
        ExecutionAttemptContext attempt = signal.getAttempt();
        BusinessContext business = signal.getBusiness();

        ExecutionLogContext.load(identity, attempt, business);

        try {
            executionClaimPort.claim(identity, attempt, business);

            log.info("[CLAIMED] signalId={}", identity.signalId());

            orderApplicationService.createOrder(identity, attempt, business, signal);

            log.info("[EXECUTION_STARTED] signalId={}", identity.signalId());

        } catch (Exception e) {
            log.error("[EXECUTION_FAILED] signalId={} msg={}",
                    identity.signalId(),
                    e.getMessage(),
                    e);
            throw e;
        } finally {
            ExecutionLogContext.clear();
        }
    }
}