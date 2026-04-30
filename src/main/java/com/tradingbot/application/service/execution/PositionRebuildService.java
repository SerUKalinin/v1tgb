package com.tradingbot.application.service.execution;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.domain.model.Trade;
import com.tradingbot.infrastructure.persistence.entity.PositionEntity;
import com.tradingbot.infrastructure.persistence.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class PositionRebuildService {

    private final TradeService tradeService;
    private final PositionRepository positionRepository;

    /**
     * Полный пересчет всех позиций из истории сделок.
     * Вызывается автоматически при полной готовности приложения.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void rebuildAllPositions() {
        log.info("[REBUILD] Starting full position rebuild from trade history...");

        // 1. Получаем все сделки, отсортированные по времени
        List<Trade> allTrades = tradeService.getAllTrades();
        if (allTrades.isEmpty()) {
            log.info("[REBUILD] No trades found. Clearing positions cache.");
            positionRepository.deleteAll();
            return;
        }

        // 2. Группируем сделки по ключу (symbol + strategyId)
        Map<String, List<Trade>> tradesByGroup = allTrades.stream()
                .collect(Collectors.groupingBy(t -> t.getSymbol() + ":" + t.getStrategyId()));

        // 3. Очищаем текущий кэш позиций
        positionRepository.deleteAllInBatch();

        // 4. Пересчитываем каждую группу
        for (Map.Entry<String, List<Trade>> entry : tradesByGroup.entrySet()) {
            String[] parts = entry.getKey().split(":");
            String symbol = parts[0];
            String strategyId = parts[1];

            PositionEntity position = calculatePosition(symbol, strategyId, entry.getValue());
            positionRepository.save(position);

            log.info("[REBUILD] Restored position for {}:{}. Qty: {}, AvgPrice: {}", 
                    symbol, strategyId, position.getQuantity(), position.getEntryPrice());
        }

        log.info("[REBUILD] Completed. Restored {} positions.", tradesByGroup.size());
    }

    /**
     * Математическое ядро пересчета позиции.
     * Реализует расчет средней цены входа (Average Entry Price).
     */
    private PositionEntity calculatePosition(String symbol, String strategyId, List<Trade> trades) {
        BigDecimal netQuantity = BigDecimal.ZERO;
        BigDecimal boughtQuantity = BigDecimal.ZERO;
        BigDecimal boughtCost = BigDecimal.ZERO;
        BigDecimal realizedPnl = BigDecimal.ZERO;

        for (Trade trade : trades) {
            BigDecimal tradeValue = trade.getPrice().multiply(trade.getQuantity());

            if (trade.getSide() == OrderSide.BUY) {
                netQuantity = netQuantity.add(trade.getQuantity());
                boughtQuantity = boughtQuantity.add(trade.getQuantity());
                boughtCost = boughtCost.add(tradeValue);
            } else { // SELL
                netQuantity = netQuantity.subtract(trade.getQuantity());

                if (boughtQuantity.compareTo(BigDecimal.ZERO) > 0) {
                    // Пропорционально уменьшаем базу стоимости покупок (Moving Average)
                    BigDecimal sellRatio = trade.getQuantity().divide(boughtQuantity, 12, RoundingMode.HALF_UP);
                    BigDecimal soldCostBasis = boughtCost.multiply(sellRatio);

                    boughtCost = boughtCost.subtract(soldCostBasis);
                    boughtQuantity = boughtQuantity.subtract(trade.getQuantity());

                    realizedPnl = realizedPnl.add(tradeValue.subtract(soldCostBasis));
                }
            }
        }

        BigDecimal avgPrice = BigDecimal.ZERO;
        if (boughtQuantity.compareTo(BigDecimal.ZERO) > 0) {
            avgPrice = boughtCost.divide(boughtQuantity, 8, RoundingMode.HALF_UP);
        }

        return PositionEntity.builder()
                .symbol(symbol)
                .strategyId(strategyId)
                .quantity(netQuantity)
                .entryPrice(avgPrice)
                .realizedPnl(realizedPnl)
                .updatedAt(Instant.now())
                .version(0L)
                .build();
    }}
