package com.tradingbot.application.service.execution;

import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.execution.ExecutionClaimPort;
import com.tradingbot.application.service.order.OrderApplicationService;
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
        String signalId = signal.getSignalId();
        executionClaimPort.claim(signalId);
        log.info("[CLAIMED] signalId={}", signalId);

        log.info("[EXECUTION_STARTED] signalId={}", signalId);
        try {
            orderApplicationService.createOrder(signal);
        } catch (Exception e) {
            log.error("[EXECUTION_FAILED] signalId={} message={}", signalId, e.getMessage(), e);
            throw e;
        }
    }
}
