package com.tradingbot.application.service.execution;

import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.domain.model.Position;
import com.tradingbot.domain.model.PositionRebuildPort;
import com.tradingbot.domain.model.Trade;
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
 *
 * <p>
 * Application layer работает только с domain model и domain ports.
 *
 * <p>
 * Контракты:
 * SYSTEM_CONTRACT.md
 * STATE_MACHINE_CONTRACT.md
 * EXECUTION_ENGINE_CONTRACT.md
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PositionRebuildService {

    private final TradeService tradeService;
    private final PositionRebuildPort positionRebuildPort;

    /**
     * Полный пересчет всех позиций из истории сделок.
     *
     * <p>
     * Источник истины — история Trade.
     * Текущая projection позиций полностью пересоздаётся.
     */
    @Transactional
    public void rebuildAllPositions() {
        log.info("[REBUILD] Starting full position rebuild from trade history...");

        List<Trade> allTrades = tradeService.getAllTrades();

        positionRebuildPort.clear();

        if (allTrades.isEmpty()) {
            log.info("[REBUILD] No trades found. Positions cleared.");
            return;
        }

        Map<String, List<Trade>> tradesByGroup =
                allTrades.stream()
                        .collect(Collectors.groupingBy(
                                trade -> trade.getSymbol() + ":" + trade.getStrategyId()
                        ));

        for (Map.Entry<String, List<Trade>> entry : tradesByGroup.entrySet()) {
            String[] parts = entry.getKey().split(":", 2);

            String symbol = parts[0];
            String strategyId = parts[1];

            RebuildResult result =
                    calculatePosition(symbol, strategyId, entry.getValue());

            positionRebuildPort.save(
                    result.position(),
                    result.realizedPnl()
            );

            log.info(
                    "[REBUILD] Restored position for {}:{}. Qty: {}, AvgPrice: {}",
                    symbol,
                    strategyId,
                    result.position().getNetQuantity(),
                    result.position().getAvgEntryPrice()
            );
        }

        log.info(
                "[REBUILD] Completed. Restored {} positions.",
                tradesByGroup.size()
        );
    }

    /**
     * Математическое ядро пересчета позиции.
     *
     * <p>
     * Реализует алгоритм средней цены входа
     * (Moving Average Cost Basis) и расчет
     * реализованного PnL.
     */
    private RebuildResult calculatePosition(
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

                netQuantity =
                        netQuantity.add(trade.getQuantity());

                boughtQuantity =
                        boughtQuantity.add(trade.getQuantity());

                boughtCost =
                        boughtCost.add(tradeValue);

            } else if (trade.getSide() == OrderSide.SELL) {

                /*
                 * Position model is long-only.
                 * SELL is valid only when sufficient
                 * position inventory exists.
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

                BigDecimal sellRatio =
                        trade.getQuantity()
                                .divide(
                                        boughtQuantity,
                                        18,
                                        RoundingMode.HALF_UP
                                );

                BigDecimal soldCostBasis =
                        boughtCost.multiply(sellRatio);

                boughtCost =
                        boughtCost.subtract(soldCostBasis);

                boughtQuantity =
                        boughtQuantity.subtract(trade.getQuantity());

                netQuantity =
                        netQuantity.subtract(trade.getQuantity());

                realizedPnl =
                        realizedPnl.add(
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
            avgPrice =
                    boughtCost.divide(
                            boughtQuantity,
                            8,
                            RoundingMode.HALF_UP
                    );
        }

        Position position =
                Position.builder()
                        .symbol(symbol)
                        .strategyId(strategyId)
                        .netQuantity(netQuantity)
                        .avgEntryPrice(avgPrice)
                        .updatedAt(Instant.now())
                        .build();

        return new RebuildResult(
                position,
                realizedPnl
        );
    }

    private record RebuildResult(
            Position position,
            BigDecimal realizedPnl
    ) {
    }
}