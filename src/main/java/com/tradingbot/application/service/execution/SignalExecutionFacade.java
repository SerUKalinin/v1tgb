package com.tradingbot.application.service.execution;

import com.tradingbot.application.service.order.OrderApplicationService;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.execution.SignalClaimPort;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.ExecutionLogContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <h1>SignalExecutionFacade</h1>
 *
 * <p>Точка входа для обработки торговых сигналов.
 * Обеспечивает бизнес-дедупликацию (SignalClaim) и инициирует создание ордера.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignalExecutionFacade {

    private final SignalClaimPort signalClaimPort;
    private final OrderApplicationService orderApplicationService;

    @Transactional
    public void execute(SignalEvent signal) {
        // 1. Бизнес-дедупликация: проверяем, не обрабатывался ли этот сигнал ранее
        if (signalClaimPort.exists(signal.getSignalId())) {
            log.debug("[DUPLICATE_SIGNAL] signalId={} ignored", signal.getSignalId());
            return;
        }

        // 2. Инициализация контекста выполнения (Root Identity)
        ExecutionContext context = signal.getExecutionContext();
        ExecutionLogContext.load(context);
        try {
            // 3. Фиксация клейма сигнала (SignalClaim protects business creation)
            signalClaimPort.claim(signal.getSignalId());
            log.info("[SIGNAL_CLAIMED] signalId={}", signal.getSignalId());

            // 4. Создание ордера и запуск бизнес-цепочки
            orderApplicationService.handleSignal(signal);
            log.info("[ORDER_CREATED] signalId={}", signal.getSignalId());
        } catch (Exception e) {            log.error("[SIGNAL_PROCESSING_FAILED] signalId={} msg={}",
                    signal.getSignalId(),
                    e.getMessage(),
                    e);
            throw e;
        } finally {
            ExecutionLogContext.clear();
        }
    }
}
