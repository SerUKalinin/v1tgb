package com.tradingbot.application.service.order;

import com.tradingbot.application.event.NewClosedCandleEvent;
import com.tradingbot.application.service.execution.PositionService;
import com.tradingbot.domain.model.Position;
import lombok.RequiredArgsConstructor;import lombok.extern.slf4j.Slf4j;
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
        List<Position> activePositions = positionService.getAllPositions().stream()
                .filter(p -> p.getSymbol().equals(symbol) && p.isOpen())
                .toList();

        for (Position position : activePositions) {
            checkExitConditions(position, high, low);
        }
    }

    private void checkExitConditions(Position position, BigDecimal high, BigDecimal low) {
        // В текущей модели Position (src/main/java/com/tradingbot/domain/model/Position.java)
        // отсутствуют поля stopLoss и takeProfit. 
        // В Stage 3 они должны быть частью доменной модели или извлекаться из метаданных.
        // Пока закомментируем логику, которая не компилируется из-за отсутствия полей в Position,
        // либо добавим их в Position.java, если это предусмотрено архитектурой.
        
        log.debug("[CLOSING-SERVICE] Checking exit for {}", position.getSymbol());
    }}
