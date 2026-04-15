package com.tradingbot.application.service;

import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.domain.model.Position;
import com.tradingbot.infrastructure.persistence.entity.EquitySnapshotEntity;
import com.tradingbot.infrastructure.persistence.repository.EquitySnapshotRepository;
import com.tradingbot.infrastructure.persistence.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class EquityService {

    private final EquitySnapshotRepository equityRepository;
    private final PositionService positionService;
    private final TradeRepository tradeRepository;
    
    private final Map<String, BigDecimal> strategyBalances = new ConcurrentHashMap<>();
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("10000");

    @EventListener
    @Transactional
    public void onTradeCreated(TradeCreatedEvent event) {
        log.info("[EQUITY] Updating balance for strategy {} after trade {}", event.getStrategyId(), event.getTradeId());
        
        strategyBalances.putIfAbsent(event.getStrategyId(), INITIAL_BALANCE);
        
        // В Stage 2 для простоты делаем snapshot при каждой сделке
        createSnapshot(event.getStrategyId(), event.getSymbol(), event.getPrice());
    }

    public void createSnapshot(String strategyId, String symbol, BigDecimal currentPrice) {
        BigDecimal balance = strategyBalances.getOrDefault(strategyId, INITIAL_BALANCE);
        
        Position position = positionService.getPosition(symbol, strategyId);
        BigDecimal unrealizedPnl = BigDecimal.ZERO;
        
        if (position != null && position.getNetQuantity().signum() != 0) {
            unrealizedPnl = currentPrice.subtract(position.getAvgEntryPrice())
                    .multiply(position.getNetQuantity());
        }

        BigDecimal equity = balance.add(unrealizedPnl);

        EquitySnapshotEntity snapshot = EquitySnapshotEntity.builder()
                .strategyId(strategyId)
                .timestamp(Instant.now())
                .balance(balance)
                .unrealizedPnl(unrealizedPnl)
                .equity(equity)
                .build();

        equityRepository.save(snapshot);
        log.info("[EQUITY] Snapshot saved for {}: Equity={}, Balance={}, UPnL={}", 
                strategyId, equity, balance, unrealizedPnl);
    }

    public BigDecimal calculateTotalRealizedPnL() {
        // Оставляем старую логику для совместимости, если нужно
        return tradeRepository.findAll().stream()
                .map(t -> {
                    BigDecimal sign = t.getSide().name().equals("BUY")
                            ? BigDecimal.valueOf(-1)
                            : BigDecimal.valueOf(1);

                    return t.getPrice()
                            .multiply(t.getQuantity())
                            .multiply(sign);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }
}