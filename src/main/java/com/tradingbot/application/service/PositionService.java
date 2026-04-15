package com.tradingbot.application.service;

import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Position;
import com.tradingbot.infrastructure.persistence.entity.PositionEntity;
import com.tradingbot.infrastructure.persistence.mapper.PositionMapper;
import com.tradingbot.infrastructure.persistence.repository.PositionRepository;
import com.tradingbot.infrastructure.persistence.repository.PositionRepository;
import com.tradingbot.infrastructure.persistence.repository.TradeRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.tradingbot.domain.event.TradeCreatedEvent;
import org.springframework.context.event.EventListener;
import com.tradingbot.common.enums.OrderSide;
import java.time.Instant;

/**
 * Сервис управления торговыми позициями.
 * <p>
 * Обеспечивает хранение, обновление и расчёт PnL позиций.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PositionService {

    private final PositionRepository repository;
    private final TradeRepository tradeRepository;
    private final PositionMapper mapper;
    private final Map<String, Position> positions = new ConcurrentHashMap<>();

    private String getCacheKey(String symbol, String strategyId) {
        return strategyId + ":" + symbol;
    }

    /**
     * Загружает позиции из базы данных при старте приложения.
     */
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
     * Слушает события о создании сделок и обновляет состояние позиции (Reducer).
     */
    @EventListener
    @Transactional
    public void onTradeCreated(TradeCreatedEvent event) {
        try {
            log.info("[POSITIONS] Reducing state for trade: {}", event.getTradeId());

            PositionEntity entity = repository.findBySymbolAndStrategyId(event.getSymbol(), event.getStrategyId())
                    .orElse(PositionEntity.builder()
                            .symbol(event.getSymbol())
                            .strategyId(event.getStrategyId())
                            .quantity(BigDecimal.ZERO)
                            .entryPrice(BigDecimal.ZERO)
                            .realizedPnl(BigDecimal.ZERO)
                            .version(0L)
                            .build());

            // Идемпотентность: проверка последнего обработанного tradeId
            if (entity.getLastTradeId() != null && entity.getLastTradeId() >= event.getTradeId()) {
                log.info("[POSITIONS] Trade {} already processed for position {}:{}. Skipping.", 
                        event.getTradeId(), event.getStrategyId(), event.getSymbol());
                return;
            }

            BigDecimal currentQty = entity.getQuantity();
            BigDecimal executedQty = event.getSide() == OrderSide.BUY 
                    ? event.getQuantity() 
                    : event.getQuantity().negate();
            BigDecimal executedPrice = event.getPrice();

            BigDecimal newQty = currentQty.add(executedQty);
            BigDecimal newEntryPrice = entity.getEntryPrice();
            BigDecimal realizedPnl = entity.getRealizedPnl();

            // Логика расчета средней цены входа и Realized PnL
            if (newQty.compareTo(BigDecimal.ZERO) == 0) {
                if (currentQty.signum() != 0) {
                    BigDecimal tradePnl = calculateTradePnl(currentQty, entity.getEntryPrice(), executedPrice);
                    realizedPnl = realizedPnl.add(tradePnl);
                }
                newEntryPrice = BigDecimal.ZERO;
            } else if (currentQty.signum() == 0) {
                newEntryPrice = executedPrice;
            } else if (currentQty.signum() == executedQty.signum()) {
                // Увеличение позиции (Long + Long или Short + Short)
                BigDecimal totalCost = entity.getEntryPrice().multiply(currentQty.abs())
                        .add(executedPrice.multiply(executedQty.abs()));
                newEntryPrice = totalCost.divide(newQty.abs(), 8, RoundingMode.HALF_UP);
            } else {
                // Частичное закрытие или переворот (Long + Short или Short + Long)
                if (currentQty.abs().compareTo(executedQty.abs()) >= 0) {
                    // Частичное закрытие без переворота
                    BigDecimal closedQty = executedQty.negate();
                    BigDecimal tradePnl = calculateTradePnl(closedQty, entity.getEntryPrice(), executedPrice);
                    realizedPnl = realizedPnl.add(tradePnl);
                    // entryPrice остается прежним
                } else {
                    // Полное закрытие старой и открытие новой в другую сторону (переворот)
                    BigDecimal closedQty = currentQty;
                    BigDecimal tradePnl = calculateTradePnl(closedQty, entity.getEntryPrice(), executedPrice);
                    realizedPnl = realizedPnl.add(tradePnl);
                    newEntryPrice = executedPrice;
                }
            }

            entity.setQuantity(newQty);
            entity.setEntryPrice(newEntryPrice);
            entity.setRealizedPnl(realizedPnl);
            entity.setLastTradeId(event.getTradeId());
            entity.setUpdatedAt(Instant.now());

            repository.save(entity);
            
            positions.put(getCacheKey(event.getSymbol(), event.getStrategyId()), mapper.toDomain(entity));

            log.info("[POSITIONS] Updated position for {} ({}): qty={} (was {}), entryPrice={}, realizedPnl={}",
                    event.getSymbol(), event.getStrategyId(), newQty, currentQty, newEntryPrice, realizedPnl);
        } catch (Exception e) {
            log.error("[POSITIONS] Critical error processing trade {}: {}", event.getTradeId(), e.getMessage(), e);
            // Мы НЕ пробрасываем исключение дальше, чтобы не ломать пайплайн
        }
    }

    /**
     * @deprecated Используйте onTradeCreated (Event-Driven)
     */
    @Deprecated
    @Transactional
    public void applyExecution(ExecutionResult result, String strategyId) {
        // Метод оставлен для обратной совместимости на время миграции, но логика перенесена в onTradeCreated
    }

    private BigDecimal calculateTradePnl(BigDecimal qty, BigDecimal entryPrice, BigDecimal exitPrice) {
        // PnL = (Exit - Entry) * Qty (для Long)
        // Если Qty отрицательный (Short), формула та же: (Exit - Entry) * (-Qty) -> (Entry - Exit) * Qty
        return exitPrice.subtract(entryPrice).multiply(qty).setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * Проверяет, есть ли открытая позиция по указанному символу.
     */
    public boolean hasOpenPosition(String symbol, String strategyId) {
        Position p = positions.get(getCacheKey(symbol, strategyId));
        return p != null && p.getNetQuantity().compareTo(BigDecimal.ZERO) != 0;
    }

    /**
     * Возвращает позицию по указанному символу.
     */
    public Position getPosition(String symbol, String strategyId) {
        return positions.get(getCacheKey(symbol, strategyId));
    }

    /**
     * Рассчитывает нереализованную прибыль/убыток по позиции.
     *
     * @param symbol       торговый символ
     * @param strategyId   идентификатор стратегии
     * @param currentPrice текущая цена
     * @return значение PnL
     */
    public BigDecimal calculatePnL(String symbol, String strategyId, BigDecimal currentPrice) {
        Position position = positions.get(getCacheKey(symbol, strategyId));
        if (position == null || position.getAvgEntryPrice().compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return currentPrice.subtract(position.getNetQuantity())
                .multiply(position.getAvgEntryPrice())
                .setScale(8, RoundingMode.HALF_UP);
    }
}