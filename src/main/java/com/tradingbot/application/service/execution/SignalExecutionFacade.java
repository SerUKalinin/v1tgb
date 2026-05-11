package com.tradingbot.application.service.execution;

import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.ExecutionLogContext;
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
        ExecutionContext context = signal.getContext();
        ExecutionLogContext.load(context);
        try {
            executionClaimPort.claim(context);
            log.info("[CLAIMED] context={}", context);

            log.info("[EXECUTION_STARTED] context={}", context);
            orderApplicationService.createOrder(context, signal);
        } catch (Exception e) {
            log.error("[EXECUTION_FAILED] context={} message={}", context, e.getMessage(), e);
            throw e;
        } finally {
            ExecutionLogContext.clear();
        }
    }}
