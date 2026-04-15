package com.tradingbot.domain.event;

import com.tradingbot.common.enums.SignalType;
import lombok.Builder;
import lombok.Value;
import java.math.BigDecimal;
import java.time.Instant;

@Value
@Builder
public class SignalEvent {
    String symbol;
    SignalType type;
    BigDecimal price;
    BigDecimal quantity;
    Instant candleTime;
    String strategyId;
}
