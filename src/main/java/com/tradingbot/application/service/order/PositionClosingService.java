package com.tradingbot.application.service.order;

import com.tradingbot.application.event.NewClosedCandleEvent;
import com.tradingbot.application.service.execution.PositionService;
import com.tradingbot.domain.model.Position;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Сервис мониторинга позиций, отвечающий за проверку условий выхода (TP/SL)
 * на основе закрытых свечей.
 *
 * <p>Является event-driven компонентом, реагирующим на закрытие свечи
 * и выполняющим проверку всех открытых позиций по соответствующему символу.</p>
 *
 * <p>Относится к execution monitoring layer и не содержит торговой логики —
 * только проверку условий выхода.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PositionClosingService {

    private final PositionService positionService;
    private final OrderManagementService oms;

    /**
     * Обрабатывает событие закрытия свечи.
     *
     * <p>Алгоритм:
     * <ul>
     *     <li>фильтрация активных позиций по символу</li>
     *     <li>проверка условий выхода (TP/SL)</li>
     * </ul>
     *
     * @param event событие закрытой свечи
     */
    @EventListener
    public void onNewCandle(NewClosedCandleEvent event) {
        String symbol = event.symbol();
        BigDecimal high = event.high();
        BigDecimal low = event.low();

        // Получаем все открытые позиции по символу
        List<Position> activePositions = positionService.getAllPositions().stream()
                .filter(p -> p.getSymbol().equals(symbol) && p.isOpen())
                .toList();

        for (Position position : activePositions) {
            checkExitConditions(position, high, low);
        }
    }

    /**
     * Проверяет условия выхода из позиции (take profit / stop loss).
     *
     * <p>На текущем этапе логика не реализована полностью из-за отсутствия
     * соответствующих полей в доменной модели Position.</p>
     *
     * @param position торговая позиция
     * @param high максимум свечи
     * @param low минимум свечи
     */
    private void checkExitConditions(Position position, BigDecimal high, BigDecimal low) {
        log.debug("[CLOSING-SERVICE] Checking exit for {}", position.getSymbol());
    }
}