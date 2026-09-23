package com.tradingbot.application.service.execution;

import com.tradingbot.application.service.order.OrderApplicationService;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.execution.AlreadyClaimedException;
import com.tradingbot.domain.execution.SignalClaimPort;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.ExecutionLogContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Фасад обработки торговых сигналов.
 *
 * <p>Является точкой входа в execution pipeline.</p>
 *
 * <p>Canonical transaction:</p>
 *
 * <pre>
 * Signal Claim
 *      ↓
 * Risk
 *      ↓
 * Order
 *      ↓
 * Outbox
 *      ↓
 * DB COMMIT
 * </pre>
 *
 * <p>Если pipeline завершается exception,
 * signal claim должен rollback вместе с транзакцией.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignalExecutionFacade {

    private final SignalClaimPort signalClaimPort;
    private final OrderApplicationService orderApplicationService;

    /**
     * Обрабатывает торговый сигнал
     * и инициирует canonical execution pipeline.
     *
     * @param signal торговый сигнал
     */
    @Transactional
    public void execute(
            SignalEvent signal
    ) {

        if (signal == null) {
            throw new IllegalArgumentException(
                    "signal не должен быть null"
            );
        }

        UUID signalId =
                signal.getSignalId();

        log.info(
                "[TRACE_FLOW] ENTER SignalExecutionFacade.execute signalId={}",
                signalId
        );

        /*
         * Fast duplicate path.
         *
         * Это не заменяет atomic claim.
         * Atomicity обеспечивается самим claim()
         * через DB constraint.
         */
        if (signalClaimPort.exists(signalId)) {

            log.debug(
                    "[DUPLICATE_SIGNAL] signalId={} ignored",
                    signalId
            );

            return;
        }

        ExecutionContext context =
                signal.getExecutionContext();

        ExecutionLogContext.load(context);

        try {

            /*
             * Claim участвует в ЭТОЙ transaction.
             *
             * Если Risk/Order/Outbox ниже упадёт,
             * claim будет rollback.
             */
            signalClaimPort.claim(
                    signalId
            );

            log.info(
                    "[SIGNAL_CLAIMED] signalId={}",
                    signalId
            );

            /*
             * Risk evaluation + Order creation.
             */
            boolean created =
                    orderApplicationService.handleSignal(
                            signal
                    );

            if (created) {

                log.info(
                        "[ORDER_CREATED] signalId={}",
                        signalId
                );

            } else {

                /*
                 * Risk rejection является успешным
                 * завершением обработки сигнала.
                 *
                 * Поэтому claim должен commit.
                 */
                log.info(
                        "[ORDER_REJECTED] signalId={}",
                        signalId
                );
            }

        } catch (AlreadyClaimedException e) {

            /*
             * Оставляем существующую семантику,
             * но не ACK-аем сигнал как успешно обработанный
             * через отдельный transaction.
             */
            log.warn(
                    "[SIGNAL_ALREADY_CLAIMED] signalId={}",
                    signalId
            );

            throw e;

        } catch (Exception e) {

            log.error(
                    "[SIGNAL_PROCESSING_FAILED] signalId={} msg={}",
                    signalId,
                    e.getMessage(),
                    e
            );

            /*
             * Обязательно rethrow.
             *
             * Это приводит к rollback SignalClaim
             * и не позволяет MarketDataService
             * подтвердить candle как processed.
             */
            throw e;

        } finally {

            ExecutionLogContext.clear();
        }
    }
}