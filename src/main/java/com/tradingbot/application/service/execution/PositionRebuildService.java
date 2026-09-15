package com.tradingbot.application.service.execution;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.domain.model.Trade;
import com.tradingbot.infrastructure.persistence.entity.PositionEntity;
import com.tradingbot.infrastructure.persistence.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Сервис пересборки позиций из истории сделок.
 * <p>
 * Используется для восстановления актуального состояния позиций при старте приложения
 * на основе полной истории торговых операций (Trade ledger).
 * <p>
 * Реализует детерминированный пересчет состояния портфеля.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PositionRebuildService {

    private final TradeService tradeService;
    private final PositionRepository positionRepository;

    /**
     * Полный пересчет всех позиций из истории сделок.
     * <p>
     * Запускается автоматически после старта приложения (ApplicationReadyEvent).
     * Выполняет полную реконструкцию состояния позиций из торговой истории.
     */
    @Transactional
    public void rebuildAllPositions() {
        log.info("[REBUILD] Starting full position rebuild from trade history...");

        List<Trade> allTrades = tradeService.getAllTrades();
        if (allTrades.isEmpty()) {
            log.info("[REBUILD] No trades found. Clearing positions cache.");
            positionRepository.deleteAll();
            return;
        }

        Map<String, List<Trade>> tradesByGroup = allTrades.stream()
                .collect(Collectors.groupingBy(t -> t.getSymbol() + ":" + t.getStrategyId()));

        positionRepository.deleteAllInBatch();

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
     * <p>
     * Реализует алгоритм средней цены входа (Moving Average Cost Basis)
     * и расчет реализованного PnL на основе последовательности сделок.
     *
     * @param symbol торговый инструмент
     * @param strategyId идентификатор стратегии
     * @param trades список сделок в хронологическом порядке
     * @return восстановленная позиция
     */
    private PositionEntity calculatePosition(
            String symbol,
            String strategyId,
            List<Trade> trades
    ) {
        BigDecimal netQuantity = BigDecimal.ZERO;
        BigDecimal boughtQuantity = BigDecimal.ZERO;
        BigDecimal boughtCost = BigDecimal.ZERO;
        BigDecimal realizedPnl = BigDecimal.ZERO;

        for (Trade trade : trades) {

            if (trade.getQuantity() == null
                    || trade.getQuantity().signum() <= 0) {
                throw new IllegalStateException(
                        String.format(
                                "Invalid trade quantity during position rebuild: " +
                                        "symbol=%s, strategyId=%s, tradeId=%s, orderId=%s, " +
                                        "exchangeTradeId=%s, side=%s, quantity=%s",
                                symbol,
                                strategyId,
                                trade.getId(),
                                trade.getOrderId(),
                                trade.getExchangeTradeId(),
                                trade.getSide(),
                                trade.getQuantity()
                        )
                );
            }

            if (trade.getPrice() == null
                    || trade.getPrice().signum() <= 0) {
                throw new IllegalStateException(
                        String.format(
                                "Invalid trade price during position rebuild: " +
                                        "symbol=%s, strategyId=%s, tradeId=%s, orderId=%s, " +
                                        "exchangeTradeId=%s, side=%s, price=%s",
                                symbol,
                                strategyId,
                                trade.getId(),
                                trade.getOrderId(),
                                trade.getExchangeTradeId(),
                                trade.getSide(),
                                trade.getPrice()
                        )
                );
            }

            BigDecimal tradeValue =
                    trade.getPrice().multiply(trade.getQuantity());

            if (trade.getSide() == OrderSide.BUY) {

                netQuantity = netQuantity.add(trade.getQuantity());
                boughtQuantity = boughtQuantity.add(trade.getQuantity());
                boughtCost = boughtCost.add(tradeValue);

            } else if (trade.getSide() == OrderSide.SELL) {

                /*
                 * Position model is long-only.
                 * SELL is valid only when there is enough existing quantity.
                 */
                if (trade.getQuantity().compareTo(netQuantity) > 0) {
                    throw new IllegalStateException(
                            String.format(
                                    "Invalid position history: SELL exceeds available position. " +
                                            "symbol=%s, strategyId=%s, tradeId=%s, orderId=%s, " +
                                            "exchangeTradeId=%s, sellQuantity=%s, availablePosition=%s",
                                    symbol,
                                    strategyId,
                                    trade.getId(),
                                    trade.getOrderId(),
                                    trade.getExchangeTradeId(),
                                    trade.getQuantity(),
                                    netQuantity
                            )
                    );
                }

                if (boughtQuantity.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalStateException(
                            String.format(
                                    "Invalid position history: SELL without BUY inventory. " +
                                            "symbol=%s, strategyId=%s, tradeId=%s, orderId=%s, " +
                                            "exchangeTradeId=%s, sellQuantity=%s, availablePosition=%s",
                                    symbol,
                                    strategyId,
                                    trade.getId(),
                                    trade.getOrderId(),
                                    trade.getExchangeTradeId(),
                                    trade.getQuantity(),
                                    netQuantity
                            )
                    );
                }

                BigDecimal sellRatio = trade.getQuantity()
                        .divide(boughtQuantity, 18, RoundingMode.HALF_UP);

                BigDecimal soldCostBasis =
                        boughtCost.multiply(sellRatio);

                boughtCost = boughtCost.subtract(soldCostBasis);
                boughtQuantity = boughtQuantity.subtract(trade.getQuantity());

                netQuantity = netQuantity.subtract(trade.getQuantity());

                realizedPnl = realizedPnl.add(
                        tradeValue.subtract(soldCostBasis)
                );

                if (netQuantity.signum() < 0) {
                    throw new IllegalStateException(
                            String.format(
                                    "Position rebuild produced negative quantity: " +
                                            "symbol=%s, strategyId=%s, tradeId=%s, " +
                                            "orderId=%s, exchangeTradeId=%s, quantity=%s",
                                    symbol,
                                    strategyId,
                                    trade.getId(),
                                    trade.getOrderId(),
                                    trade.getExchangeTradeId(),
                                    netQuantity
                            )
                    );
                }

            } else {
                throw new IllegalStateException(
                        String.format(
                                "Unsupported trade side during position rebuild: " +
                                        "symbol=%s, strategyId=%s, tradeId=%s, side=%s",
                                symbol,
                                strategyId,
                                trade.getId(),
                                trade.getSide()
                        )
                );
            }
        }

        BigDecimal avgPrice = BigDecimal.ZERO;

        if (boughtQuantity.compareTo(BigDecimal.ZERO) > 0) {
            avgPrice = boughtCost.divide(
                    boughtQuantity,
                    8,
                    RoundingMode.HALF_UP
            );
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
    }
}