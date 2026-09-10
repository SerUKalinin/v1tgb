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
 * Фасад обработки торговых сигналов.
 * <p>
 * Является точкой входа в execution pipeline и отвечает за:
 * <ul>
 *     <li>идемпотентность на уровне бизнес-сигналов (SignalClaim)</li>
 *     <li>инициализацию execution context</li>
 *     <li>делегирование создания ордера в application layer</li>
 *     <li>управление жизненным циклом обработки сигнала</li>
 * </ul>
 * <p>
 * Гарантирует, что один сигнал будет обработан только один раз.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignalExecutionFacade {

    private final SignalClaimPort signalClaimPort;
    private final OrderApplicationService orderApplicationService;

    /**
     * Обрабатывает торговый сигнал и инициирует создание ордера.
     *
     * @param signal торговый сигнал из доменного слоя
     */
    @Transactional
    public void execute(SignalEvent signal) {

        // 1. Проверка идемпотентности сигнала
        if (signalClaimPort.exists(signal.getSignalId())) {
            log.debug("[DUPLICATE_SIGNAL] signalId={} ignored", signal.getSignalId());
            return;
        }

        // 2. Инициализация execution контекста
        ExecutionContext context = signal.getExecutionContext();
        ExecutionLogContext.load(context);

        try {
            // 3. Фиксация сигнала как обработанного
            signalClaimPort.claim(signal.getSignalId());
            log.info("[SIGNAL_CLAIMED] signalId={}", signal.getSignalId());

            // 4. Создание ордера и запуск бизнес-цепочки
            orderApplicationService.handleSignal(signal);
            log.info("[ORDER_CREATED] signalId={}", signal.getSignalId());

        } catch (Exception e) {
            log.error("[SIGNAL_PROCESSING_FAILED] signalId={} msg={}",
                    signal.getSignalId(),
                    e.getMessage(),
                    e);
            throw e;
        } finally {
            ExecutionLogContext.clear();
        }
    }
}