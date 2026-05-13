package com.tradingbot.domain.event;

import com.tradingbot.common.enums.SignalType;
import com.tradingbot.tracing.IdentityContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.IdentityFactory;
import com.tradingbot.tracing.ExecutionContext;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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
                ExecutionAttemptContext.of(IdentityFactory.deriveExecution(signalId, 0)),
                BusinessContext.empty(),
                1
        );

        this.executionContext = new ExecutionContext(getIdentity(), getAttempt(), getBusiness());
        this.symbol = symbol;
        this.type = type;
        this.price = price;
        this.quantity = quantity;
        this.stopLoss = stopLoss;
        this.takeProfit = takeProfit;
        this.candleTime = candleTime;
        this.strategyId = strategyId;

        // Strict invariant check
        if (getIdentity().signalId().equals(getAttempt().executionId())) {
            throw new IllegalStateException("Identity corruption: executionId must not equal signalId");
        }
    }

    @Override
    public String getEventType() {
        return "SIGNAL_RECEIVED";
    }

    public UUID getSignalId() {
        return getIdentity().signalId();
    }
}
