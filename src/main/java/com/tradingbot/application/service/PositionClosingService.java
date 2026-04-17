package com.tradingbot.application.service;

import com.tradingbot.application.event.NewClosedCandleEvent;
import com.tradingbot.domain.position.PositionState;
import com.tradingbot.domain.position.PositionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Слой мониторинга: проверяет условия выхода (TP/SL) по каждой закрытой свече.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PositionClosingService {

    private final PositionService positionService;
    private final OrderManagementService oms;

    @EventListener
    public void onNewCandle(NewClosedCandleEvent event) {
        String symbol = event.symbol();
        BigDecimal high = event.high();
        BigDecimal low = event.low();

        // Получаем все открытые позиции по данному символу
        List<PositionState> activePositions = positionService.getAllPositions().stream()
                .filter(p -> p.symbol().equals(symbol) && p.status() == PositionStatus.OPEN)
                .toList();

        for (PositionState position : activePositions) {
            checkExitConditions(position, high, low);
        }
    }

    private void checkExitConditions(PositionState position, BigDecimal high, BigDecimal low) {
        // 1. Проверка Take Profit
        if (position.takeProfit() != null && position.takeProfit().signum() > 0) {
            boolean tpTriggered = position.netQuantity().signum() > 0 
                ? high.compareTo(position.takeProfit()) >= 0  // Long: High >= TP
                : low.compareTo(position.takeProfit()) <= 0;  // Short: Low <= TP

            if (tpTriggered) {
                log.info("[CLOSING-SERVICE] Take Profit triggered for {} at price {}", position.symbol(), position.takeProfit());
                oms.closePosition(position);
                return;
            }
        }

        // 2. Проверка Stop Loss
        if (position.stopLoss() != null && position.stopLoss().signum() > 0) {
            boolean slTriggered = position.netQuantity().signum() > 0
                ? low.compareTo(position.stopLoss()) <= 0     // Long: Low <= SL
                : high.compareTo(position.stopLoss()) >= 0;   // Short: High >= SL

            if (slTriggered) {
                log.info("[CLOSING-SERVICE] Stop Loss triggered for {} at price {}", position.symbol(), position.stopLoss());
                oms.closePosition(position);
            }
        }
    }
}
