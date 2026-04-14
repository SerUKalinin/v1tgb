package com.tradingbot.application;

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
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class PositionService {

    private final PositionRepository repository;
    private final PositionMapper mapper;
    private final Map<String, Position> positions = new ConcurrentHashMap<>();

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
            // Расчет средней цены входа: (P1*Q1 + P2*Q2) / (Q1+Q2)
            newEntryPrice = current.getEntryPrice().multiply(current.getQuantity())
                    .add(result.getExecutedPrice().multiply(result.getExecutedQty()))
                    .divide(newQty, 8, RoundingMode.HALF_UP);
        }

        Position updated = new Position(result.getSymbol(), newQty, newEntryPrice);
        positions.put(result.getSymbol(), updated);
        
        // Сохранение в БД
        repository.save(mapper.toEntity(updated));
        log.info("[POSITIONS] Updated position for {}: qty={}, entryPrice={}", 
                updated.getSymbol(), updated.getQuantity(), updated.getEntryPrice());
    }

    public boolean hasOpenPosition(String symbol) {
        return positions.containsKey(symbol) && positions.get(symbol).getQuantity().compareTo(BigDecimal.ZERO) > 0;
    }

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