package com.tradingbot.application.service;

import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.domain.model.Position;
import com.tradingbot.domain.model.PositionEntity;
import com.tradingbot.domain.model.PositionStatus;
import com.tradingbot.domain.position.PositionReducer;
import com.tradingbot.domain.position.PositionState;
import com.tradingbot.infrastructure.concurrent.PartitionLockManager;
import com.tradingbot.infrastructure.persistence.mapper.PositionMapper;
import com.tradingbot.infrastructure.persistence.repository.PositionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Сервис управления торговыми позициями.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PositionService {
    private final PositionRepository repository;
    private final PositionMapper mapper;
    private final PartitionLockManager lockManager;
    private final PositionReducer reducer;
    private final Map<String, Position> positions = new ConcurrentHashMap<>();

    private String getCacheKey(String symbol, String strategyId) {
        return strategyId + ":" + symbol;
    }

    @PostConstruct
    public void loadPositions() {
        log.info("[POSITIONS] Loading positions from database...");
        try {
            List<PositionEntity> entities = repository.findAll();
            entities.forEach(entity -> {
                Position domain = mapper.toDomain(entity);
                positions.put(getCacheKey(domain.getSymbol(), entity.getStrategyId()), domain);
            });
            log.info("[POSITIONS] Loaded {} positions", positions.size());
        } catch (Exception e) {
            log.error("[POSITIONS] Failed to load positions: {}", e.getMessage());
        }
    }

    @EventListener
    @Transactional
    public void onTradeCreated(TradeCreatedEvent event) {
        String lockKey = event.getStrategyId() + ":" + event.getSymbol();
        ReentrantLock lock = lockManager.getLock(lockKey);

        lock.lock();
        try {
            log.info("[POSITIONS] Processing trade {} for {}", event.getTradeId(), lockKey);

            PositionEntity entity = repository.findBySymbolAndStrategyId(event.getSymbol(), event.getStrategyId())
                    .orElseGet(() -> createNewPositionEntity(event));

            if (entity.getLastTradeId() != null && entity.getLastTradeId() >= event.getTradeId()) {
                return;
            }

            PositionState currentState = new PositionState(
                    entity.getSymbol(),
                    entity.getStrategyId(),
                    entity.getQuantity(),
                    entity.getEntryPrice(),
                    entity.getLastTradeId(),
                    entity.getRealizedPnl(),
                    entity.getStopLoss(),
                    entity.getTakeProfit(),
                    entity.getStatus() != null ? PositionStatus.valueOf(entity.getStatus()) : PositionStatus.NEW,
                    entity.getCloseRequestId(),
                    entity.getUpdatedAt()
            );

            PositionState newState = reducer.reduce(currentState, event);

            entity.setQuantity(newState.netQuantity());
            entity.setEntryPrice(newState.averagePrice());
            entity.setRealizedPnl(newState.realizedPnl());
            entity.setLastTradeId(newState.lastTradeId());
            entity.setStopLoss(newState.stopLoss());
            entity.setTakeProfit(newState.takeProfit());
            entity.setStatus(newState.status().name());
            entity.setCloseRequestId(newState.closeRequestId());
            entity.setUpdatedAt(newState.updatedAt());

            repository.save(entity);
            positions.put(lockKey, mapper.toDomain(entity));

        } finally {
            lock.unlock();
        }
    }

    private PositionEntity createNewPositionEntity(TradeCreatedEvent event) {
        return PositionEntity.builder()
                .symbol(event.getSymbol())
                .strategyId(event.getStrategyId())
                .quantity(BigDecimal.ZERO)
                .entryPrice(BigDecimal.ZERO)
                .realizedPnl(BigDecimal.ZERO)
                .version(0L)
                .build();
    }

    public boolean hasOpenPosition(String symbol, String strategyId) {
        Position p = positions.get(getCacheKey(symbol, strategyId));
        return p != null && p.getNetQuantity().compareTo(BigDecimal.ZERO) != 0;
    }

    public List<Position> getAllPositions() {
        return List.copyOf(positions.values());
    }

    public BigDecimal calculatePnL(String symbol, String strategyId, BigDecimal currentPrice) {
        Position position = positions.get(getCacheKey(symbol, strategyId));
        if (position == null || position.getNetQuantity().signum() == 0) return BigDecimal.ZERO;
        
        return currentPrice.subtract(position.getAvgEntryPrice())
                .multiply(position.getNetQuantity())
                .setScale(8, RoundingMode.HALF_UP);
    }

    public com.tradingbot.domain.model.Position getPosition(String symbol, String strategyId) {
        return positions.get(getCacheKey(symbol, strategyId));
    }

    public com.tradingbot.domain.position.PortfolioState getPortfolioState() {
        Map<String, com.tradingbot.domain.model.Position> activePositions = positions.entrySet().stream()
                .filter(entry -> entry.getValue().getNetQuantity().compareTo(BigDecimal.ZERO) != 0)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue
                ));
        return new com.tradingbot.domain.position.PortfolioState(activePositions);
    }
}