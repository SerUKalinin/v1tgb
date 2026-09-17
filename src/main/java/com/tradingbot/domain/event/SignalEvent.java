package com.tradingbot.domain.event;

import com.tradingbot.common.enums.SignalType;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.ExecutionContext;
import com.tradingbot.tracing.IdentityContext;
import com.tradingbot.tracing.IdentityFactory;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Доменное событие генерации торгового сигнала.
 *
 * <p>Фиксирует результат работы стратегии и является входной точкой
 * для execution pipeline системы.</p>
 *
 * <p>Содержит:
 * <ul>
 *     <li>тип сигнала</li>
 *     <li>рыночные параметры</li>
 *     <li>контекст исполнения</li>
 *     <li>идентификацию для трассировки</li>
 * </ul>
 */
@Getter
public class SignalEvent extends DomainEvent {

    private final String symbol;
    private final SignalType type;
    private final BigDecimal price;
    private final BigDecimal quantity;
    private final BigDecimal stopLoss;
    private final BigDecimal takeProfit;
    private final Instant candleTime;
    private final String strategyId;

    private final ExecutionContext executionContext;

    /**
     * Создаёт доменное событие сигнала.
     *
     * <p>Execution identity строится по канонической цепочке SSOT:</p>
     *
     * <pre>
     * signalId
     *     ↓
     * orderId = deriveOrder(signalId)
     *     ↓
     * executionId = deriveExecution(orderId, 1)
     * </pre>
     *
     * @param signalId идентификатор сигнала
     * @param symbol торговый символ
     * @param type тип сигнала
     * @param price цена
     * @param quantity объём
     * @param stopLoss стоп-лосс
     * @param takeProfit тейк-профит
     * @param candleTime время свечи
     * @param strategyId идентификатор стратегии
     */
    public SignalEvent(
            UUID signalId,
            String symbol,
            SignalType type,
            BigDecimal price,
            BigDecimal quantity,
            BigDecimal stopLoss,
            BigDecimal takeProfit,
            Instant candleTime,
            String strategyId
    ) {
        super(
                IdentityContext.of(signalId),
                ExecutionAttemptContext.firstAttempt(
                        IdentityFactory.deriveExecution(
                                IdentityFactory.deriveOrder(signalId),
                                1
                        ),
                        signalId
                ),
                BusinessContext.empty(),
                "SIGNAL_RECEIVED",
                1
        );

        this.executionContext = new ExecutionContext(
                getIdentity(),
                getAttempt(),
                getBusiness()
        );

        this.symbol = symbol;
        this.type = type;
        this.price = price;
        this.quantity = quantity;
        this.stopLoss = stopLoss;
        this.takeProfit = takeProfit;
        this.candleTime = candleTime;
        this.strategyId = strategyId;

        UUID expectedExecutionId =
                IdentityFactory.deriveExecution(
                        IdentityFactory.deriveOrder(signalId),
                        1
                );

        if (!expectedExecutionId.equals(getAttempt().executionId())) {
            throw new IllegalStateException(
                    "Identity corruption: SignalEvent executionId does not match canonical order execution identity"
            );
        }
    }

    /**
     * Тип события в доменной системе.
     *
     * @return SIGNAL_RECEIVED
     */
    @Override
    public String getEventType() {
        return "SIGNAL_RECEIVED";
    }

    /**
     * Удобный доступ к signalId.
     *
     * @return идентификатор сигнала
     */
    public UUID getSignalId() {
        return getIdentity().signalId();
    }
}