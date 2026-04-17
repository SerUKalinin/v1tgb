package com.tradingbot.application.service;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.domain.model.Position;
import com.tradingbot.domain.position.PositionReducer;
import com.tradingbot.domain.position.PositionState;
import com.tradingbot.infrastructure.concurrent.PartitionLockManager;
import com.tradingbot.infrastructure.persistence.entity.PositionEntity;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Сервис управления торговыми позициями.
 * Использует Partitioning Lock для обеспечения детерминированности и исключения гонок.
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

    /**
     * Слушает события о создании сделок и обновляет состояние позиции.
     * Гарантирует последовательную обработку через Striped Lock по символу и стратегии.
     */
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

            // 1. Strict Idempotency Check
            if (entity.getLastTradeId() != null && entity.getLastTradeId() >= event.getTradeId()) {
                log.warn("[POSITIONS] Trade {} already processed or older. Skipping. (Last: {})", 
                        event.getTradeId(), entity.getLastTradeId());
                return;
            }

            // 2. Pure State Transition
            PositionState currentState = new PositionState(
                    entity.getSymbol(),
                    entity.getStrategyId(),
                    entity.getQuantity(),
                    entity.getEntryPrice(),
                    entity.getLastTradeId(),
                    entity.getRealizedPnl(),
                    entity.getUpdatedAt()
            );

            PositionState newState = reducer.reduce(currentState, event);

            // 3. Update Managed Entity
            entity.setQuantity(newState.netQuantity());
            entity.setEntryPrice(newState.averagePrice());
            entity.setRealizedPnl(newState.realizedPnl());
            entity.setLastTradeId(newState.lastTradeId());
            entity.setUpdatedAt(newState.updatedAt());

            // 4. Save & Sync Cache
            repository.save(entity);
            positions.put(lockKey, mapper.toDomain(entity));

            log.info("[POSITIONS] Updated {}: Qty {} -> {}, PnL: +{}", 
                    lockKey, currentState.netQuantity(), newState.netQuantity(), 
                    newState.realizedPnl().subtract(currentState.realizedPnl()));

        } catch (Exception e) {
            log.error("[POSITIONS] Critical error processing trade {}: {}", event.getTradeId(), e.getMessage(), e);
            throw e; // Rollback transaction
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

    public Position getPosition(String symbol, String strategyId) {
        return positions.get(getCacheKey(symbol, strategyId));
    }

    public BigDecimal calculatePnL(String symbol, String strategyId, BigDecimal currentPrice) {
        Position position = positions.get(getCacheKey(symbol, strategyId));
        if (position == null || position.getNetQuantity().signum() == 0) return BigDecimal.ZERO;
        
        return currentPrice.subtract(position.getAvgEntryPrice())
                .multiply(position.getNetQuantity())
                .setScale(8, RoundingMode.HALF_UP);
    }
}