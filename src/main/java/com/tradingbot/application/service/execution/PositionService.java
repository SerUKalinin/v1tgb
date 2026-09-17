package com.tradingbot.application.service.execution;

import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.domain.model.Position;
import com.tradingbot.domain.model.PositionPort;
import com.tradingbot.infrastructure.concurrent.PartitionLockManager;
import com.tradingbot.infrastructure.outbox.IdempotencyService;
import com.tradingbot.tracing.IdentityFactory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Application service для работы с торговыми позициями.
 *
 * <p>Persistence скрыт за {@link PositionPort}.
 * Application layer не должен зависеть от JPA Entity/Repository/Mapper.
 *
 * <p>Контракт:
 * SYSTEM_CONTRACT.md
 * STATE_MACHINE_CONTRACT.md
 * EXECUTION_ENGINE_CONTRACT.md
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PositionService {

    private final PositionPort positionPort;
    private final PartitionLockManager lockManager;
    private final IdempotencyService idempotencyService;

    private final Map<String, Position> positions = new ConcurrentHashMap<>();

    private String getCacheKey(String symbol, String strategyId) {
        return strategyId + ":" + symbol;
    }

    /**
     * Загружает позиции из persistence boundary в локальный cache.
     */
    @PostConstruct
    public void loadPositions() {
        log.info("[POSITIONS] Loading positions from database...");

        try {
            List<Position> loadedPositions = positionPort.findAll();

            loadedPositions.forEach(position ->
                    positions.put(
                            getCacheKey(position.getSymbol(), position.getStrategyId()),
                            position
                    )
            );

            log.info(
                    "[POSITIONS] Loaded {} positions",
                    positions.size()
            );

        } catch (Exception e) {
            log.error(
                    "[POSITIONS] Failed to load positions: {}",
                    e.getMessage(),
                    e
            );
        }
    }

    /**
     * Обновляет projection позиции по TradeCreatedEvent.
     *
     * <p>Idempotency marker и business mutation остаются частью одной
     * application transaction.
     */
    @Transactional
    public void updatePosition(
            UUID eventId,
            TradeCreatedEvent event
    ) {
        if (eventId == null) {
            throw new IllegalArgumentException(
                    "eventId cannot be null"
            );
        }

        if (event == null) {
            throw new IllegalArgumentException(
                    "event cannot be null"
            );
        }

        String lockKey =
                event.getStrategyId()
                        + ":"
                        + event.getSymbol();

        ReentrantLock lock =
                lockManager.getLock(lockKey);

        lock.lock();

        try {
            log.info(
                    "[POSITIONS] Updating projection for event {} trade {} on {}",
                    eventId,
                    event.getTradeId(),
                    lockKey
            );

            /*
             * Idempotency относится к Outbox event,
             * а не к business tradeId.
             */
            if (idempotencyService.isAlreadyProcessed(eventId)) {
                log.warn(
                        "[POSITIONS] Event {} already processed. Skipping trade {}.",
                        eventId,
                        event.getTradeId()
                );
                return;
            }

            Position position =
                    positionPort.applyTrade(event);

            /*
             * Business mutation и idempotency marker
             * находятся в одной transaction.
             */
            idempotencyService.markAsProcessed(
                    eventId,
                    "PositionService"
            );

            positions.put(
                    lockKey,
                    position
            );

            log.info(
                    "[POSITIONS] Projection updated for symbol={} strategy={} quantity={}",
                    position.getSymbol(),
                    position.getStrategyId(),
                    position.getNetQuantity()
            );

        } finally {
            lock.unlock();
        }
    }

    public boolean hasOpenPosition(
            String symbol,
            String strategyId
    ) {
        Position position =
                positions.get(
                        getCacheKey(symbol, strategyId)
                );

        return position != null
                && position.getNetQuantity() != null
                && position.getNetQuantity().compareTo(BigDecimal.ZERO) != 0;
    }

    public Position getPosition(
            String symbol,
            String strategyId
    ) {
        String key =
                getCacheKey(symbol, strategyId);

        Position cached =
                positions.get(key);

        if (cached != null) {
            return cached;
        }

        return positionPort
                .findBySymbolAndStrategyId(symbol, strategyId)
                .map(position -> {
                    positions.put(key, position);
                    return position;
                })
                .orElse(null);
    }

    public BigDecimal calculatePnL(
            String symbol,
            String strategyId,
            BigDecimal currentPrice
    ) {
        Position position =
                positions.get(
                        getCacheKey(symbol, strategyId)
                );

        if (position == null
                || position.getNetQuantity() == null
                || position.getNetQuantity().signum() == 0) {

            return BigDecimal.ZERO;
        }

        return currentPrice
                .subtract(position.getAvgEntryPrice())
                .multiply(position.getNetQuantity())
                .setScale(8, RoundingMode.HALF_UP);
    }

    public List<Position> getAllPositions() {
        return List.copyOf(
                positions.values()
        );
    }
}