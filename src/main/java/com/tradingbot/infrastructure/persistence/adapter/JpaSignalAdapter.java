package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.model.SignalPort;
import com.tradingbot.infrastructure.persistence.entity.SignalEntity;
import com.tradingbot.infrastructure.persistence.repository.SignalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class JpaSignalAdapter implements SignalPort {

    private final SignalRepository signalRepository;

    @Override
    @Transactional
    public void save(SignalEvent signal) {
        if (signal == null) {
            throw new IllegalArgumentException("signal cannot be null");
        }

        BigDecimal price = signal.getPrice();

        BigDecimal takeProfit1 =
                signal.getTakeProfit() != null
                        ? signal.getTakeProfit()
                        : price.multiply(BigDecimal.valueOf(1.0005));

        BigDecimal takeProfit2 =
                price.multiply(BigDecimal.valueOf(1.0010));

        BigDecimal stopLoss =
                signal.getStopLoss() != null
                        ? signal.getStopLoss()
                        : price.multiply(BigDecimal.valueOf(0.9995));

        SignalEntity entity = SignalEntity.builder()
                .id(signal.getSignalId())
                .symbol(signal.getSymbol())
                .type(signal.getType())
                .price(price)
                .takeProfit1(takeProfit1)
                .takeProfit2(takeProfit2)
                .stopLoss(stopLoss)
                .strategyId(signal.getStrategyId())
                .createdAt(Instant.now())
                .timestamp(signal.getCandleTime())
                .build();

        signalRepository.save(entity);
    }
}