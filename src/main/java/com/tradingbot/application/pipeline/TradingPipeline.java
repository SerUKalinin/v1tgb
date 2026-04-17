package com.tradingbot.application.pipeline;

import com.tradingbot.application.service.AdminNotificationService;
import com.tradingbot.application.service.OrderManagementService;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.model.*;
import com.tradingbot.domain.risk.RiskDecision;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.domain.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Торговый конвейер, объединяющий этапы обработки сигнала.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TradingPipeline {

    private final TradingStrategy strategy;
    private final RiskManager riskManager;
    private final OrderManagementService orderManagementService;
    private final ApplicationEventPublisher eventPublisher;
    private final AdminNotificationService adminNotificationService;
    private final OrderRepository orderRepository;

    /**
     * Обрабатывает свечное окно через торговый конвейер.
     *
     * @param window окно свечей
     */
    public void process(CandleWindow window) {
        try {
            if (window.getCandles().isEmpty()) return;

            Candle lastCandle = window.getLast();
            log.info("[PIPELINE] Processing symbol={} last_close={}", window.getSymbol(), lastCandle.getClose());

            // 1. Strategy (Stateless Decision)
            Signal signal = strategy.analyze(window);
            if (signal == null || signal.getType() == SignalType.HOLD) {
                return;
            }

            // 🔥 PUBLISH SIGNAL EVENT (For Product Layer / Telegram)
            eventPublisher.publishEvent(SignalEvent.builder()
                    .symbol(signal.getSymbol())
                    .type(signal.getType())
                    .price(signal.getPrice())
                    .strategyId(signal.getStrategyId())
                    .candleTime(window.getLast().getOpenTime())
                    .build());

            // 2. Risk (Stateless Decision)
            RiskDecision decision = riskManager.evaluate(signal);
            if (!decision.isApproved()) {
                log.warn("[PIPELINE] Risk rejected: {}", decision.getReason());
                adminNotificationService.notifyRiskEvent(
                    String.format("Сигнал %s %s отклонен: %s", signal.getSymbol(), signal.getType(), decision.getReason())
                );
                return;
            }

            // 3. Prepare Order Request (DTO)
            String clientOrderId = generateClientOrderId(window);
            
            // 🔥 IDEMPOTENCY CHECK
            if (orderRepository.existsByClientOrderId(clientOrderId)) {
                log.warn("[PIPELINE] Order with clientOrderId {} already exists. Skipping duplicate.", clientOrderId);
                return;
            }

            OrderRequest request = OrderRequest.builder()
                    .symbol(signal.getSymbol())
                    .side(signal.getType() == SignalType.BUY ? OrderSide.BUY : OrderSide.SELL)
                    .amount(decision.getAmount())
                    .price(signal.getPrice())
                    .strategyId("simple-strategy")
                    .clientOrderId(clientOrderId)
                    .build();

            // 4. Delegate to OMS (Stateful Transactional Boundary)
            orderManagementService.executeOrder(request);
        } catch (Exception e) {
            log.error("[PIPELINE] Error processing candle for {}", window.getSymbol(), e);
            adminNotificationService.notifyCriticalError(
                "TradingPipeline: " + window.getSymbol(),
                e.getMessage()
            );
        }
    }
    private String generateClientOrderId(CandleWindow window) {
        return String.format("%s_%s", window.getSymbol(), window.getLast().getOpenTime().toEpochMilli());
    }
}