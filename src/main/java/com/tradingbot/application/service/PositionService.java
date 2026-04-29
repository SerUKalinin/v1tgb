package com.tradingbot.application.service;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.domain.model.Position;
import com.tradingbot.domain.position.PositionReducer;
import com.tradingbot.domain.position.PositionState;
import com.tradingbot.infrastructure.concurrent.PartitionLockManager;
import com.tradingbot.infrastructure.outbox.IdempotencyService;
import com.tradingbot.infrastructure.persistence.entity.PositionEntity;
import com.tradingbot.infrastructure.persistence.mapper.PositionMapper;
import com.tradingbot.infrastructure.persistence.repository.PositionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.UUID;

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
    private final IdempotencyService idempotencyService;
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
     * Обновляет состояние позиции на основе сделки.
     * Теперь вызывается через Outbox Consumer (PositionProjectionHandler).
     */
    @Transactional
    public void updatePosition(TradeCreatedEvent event) {
        String lockKey = event.getStrategyId() + ":" + event.getSymbol();
        ReentrantLock lock = lockManager.getLock(lockKey);
        lock.lock();
        try {
            log.info("[POSITIONS] Updating projection for trade {} on {}", event.getTradeId(), lockKey);

            // 1. Production-grade Idempotency Guard (Global Processed Events)
            if (idempotencyService.isAlreadyProcessed(event.getTradeId())) {
                log.warn("[POSITIONS] Trade {} already processed globally. Skipping.", event.getTradeId());
                return;
            }

            PositionEntity entity = repository.findBySymbolAndStrategyId(event.getSymbol(), event.getStrategyId())
                    .orElseGet(() -> createNewPositionEntity(event));

            // 2. Pure State Transition & Entity Update
            entity.applyTrade(event.getQuantity(), event.getPrice(), event.getTradeId());
            
            // Update additional fields not handled by applyTrade if necessary
            // (In a real scenario, reducer logic would be fully moved to Entity)
            entity.updateStopLoss(event.getStopLoss());
            entity.updateTakeProfit(event.getTakeProfit());

            // 4. Save & Sync Cache
            repository.save(entity);
            idempotencyService.markAsProcessed(event.getTradeId(), "PositionService");
            positions.put(lockKey, mapper.toDomain(entity));
        } finally {
            lock.unlock();
        }
    }

    private PositionEntity createNewPositionEntity(TradeCreatedEvent event) {
        return PositionEntity.builder()
                .id(UUID.randomUUID())
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
        String key = getCacheKey(symbol, strategyId);
        Position cached = positions.get(key);
        if (cached != null) return cached;

        // Fallback: try to load from DB if cache is empty (e.g. during integration tests)
        return repository.findBySymbolAndStrategyId(symbol, strategyId)
                .map(entity -> {
                    Position domain = mapper.toDomain(entity);
                    positions.put(key, domain);
                    return domain;
                })
                .orElse(null);
    }

    public BigDecimal calculatePnL(String symbol, String strategyId, BigDecimal currentPrice) {
        Position position = positions.get(getCacheKey(symbol, strategyId));
        if (position == null || position.getNetQuantity().signum() == 0) return BigDecimal.ZERO;
        
        return currentPrice.subtract(position.getAvgEntryPrice())
                .multiply(position.getNetQuantity())
                .setScale(8, RoundingMode.HALF_UP);
    }

    public List<Position> getAllPositions() {
        return List.copyOf(positions.values());
    }
}