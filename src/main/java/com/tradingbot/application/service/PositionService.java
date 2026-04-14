package com.tradingbot.application.service;

import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Position;
import com.tradingbot.infrastructure.persistence.entity.PositionEntity;
import com.tradingbot.infrastructure.persistence.mapper.PositionMapper;
import com.tradingbot.infrastructure.persistence.repository.PositionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private final PositionMapper mapper;
    private final Map<String, Position> positions = new ConcurrentHashMap<>();

    /**
     * Загружает позиции из базы данных при старте приложения.
     */
    @PostConstruct
    public void loadPositions() {
        log.info("[POSITIONS] Loading positions from database...");
        List<PositionEntity> entities = repository.findAll();
        entities.forEach(entity -> {
            Position domain = mapper.toDomain(entity);
            positions.put(domain.getSymbol(), domain);
        });
        log.info("[POSITIONS] Loaded {} positions", positions.size());
    }

    /**
     * Применяет результат исполнения ордера к позиции.
     *
     * @param result результат исполнения
     */
    public void applyExecution(ExecutionResult result) {
        Position current = positions.get(result.getSymbol());

        if (current == null) {
            current = new Position(result.getSymbol(), BigDecimal.ZERO, BigDecimal.ZERO);
        }

        BigDecimal newQty = current.getQuantity().add(result.getExecutedQty());
        BigDecimal newEntryPrice;

        if (newQty.compareTo(BigDecimal.ZERO) == 0) {
            newEntryPrice = BigDecimal.ZERO;
        } else if (current.getQuantity().compareTo(BigDecimal.ZERO) == 0) {
            newEntryPrice = result.getExecutedPrice();
        } else {
            newEntryPrice = current.getEntryPrice().multiply(current.getQuantity())
                    .add(result.getExecutedPrice().multiply(result.getExecutedQty()))
                    .divide(newQty, 8, RoundingMode.HALF_UP);
        }

        Position updated = new Position(result.getSymbol(), newQty, newEntryPrice);
        positions.put(result.getSymbol(), updated);

        repository.save(mapper.toEntity(updated));
        log.info("[POSITIONS] Updated position for {}: qty={}, entryPrice={}",
                updated.getSymbol(), updated.getQuantity(), updated.getEntryPrice());
    }

    /**
     * Проверяет, есть ли открытая позиция по указанному символу.
     *
     * @param symbol торговый символ
     * @return true, если позиция открыта
     */
    public boolean hasOpenPosition(String symbol) {
        return positions.containsKey(symbol) && positions.get(symbol).getQuantity().compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Возвращает позицию по указанному символу.
     *
     * @param symbol торговый символ
     * @return позиция или null
     */
    public Position getPosition(String symbol) {
        return positions.get(symbol);
    }

    /**
     * Рассчитывает нереализованную прибыль/убыток по позиции.
     *
     * @param symbol       торговый символ
     * @param currentPrice текущая цена
     * @return значение PnL
     */
    public BigDecimal calculatePnL(String symbol, BigDecimal currentPrice) {
        Position position = positions.get(symbol);
        if (position == null || position.getQuantity().compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return currentPrice.subtract(position.getEntryPrice())
                .multiply(position.getQuantity())
                .setScale(8, RoundingMode.HALF_UP);
    }
}