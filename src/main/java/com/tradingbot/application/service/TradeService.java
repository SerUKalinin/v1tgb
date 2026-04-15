package com.tradingbot.application.service;

import com.tradingbot.domain.event.OrderFilledEvent;
import com.tradingbot.domain.event.TradeCreatedEvent;
import com.tradingbot.domain.model.Trade;
import com.tradingbot.domain.risk.RiskStateStore;
import com.tradingbot.infrastructure.persistence.entity.TradeEntity;
import com.tradingbot.infrastructure.persistence.mapper.TradeMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class TradeService {

    private final TradeRepository tradeRepository;
    private final TradeMapper tradeMapper;
    private final ApplicationEventPublisher eventPublisher;

    private final OrderRepository orderRepository;
    private final RiskStateStore riskStateStore;

    /**
     * Слушает события об исполнении ордеров и регистрирует сделки.
     */
    @EventListener
    @Transactional
    public void onOrderFilled(OrderFilledEvent event) {
        log.info("[TRADE-SERVICE] Handling OrderFilledEvent for order: {}", event.getOrderId());
        
        if (tradeRepository.existsByExchangeTradeId(event.getExternalExecutionId())) {
            log.warn("[TRADE-SERVICE] Duplicate trade detected: {}. Skipping.", event.getExternalExecutionId());
            return;
        }

        var order = orderRepository.findById(event.getOrderId())
                .orElseThrow(() -> new RuntimeException("Order not found: " + event.getOrderId()));

        TradeEntity entity = new TradeEntity();
        entity.setOrderId(event.getOrderId());
        entity.setClientOrderId(order.getClientOrderId());
        entity.setExchangeTradeId(event.getExternalExecutionId());
        entity.setSymbol(event.getSymbol());
        entity.setQuantity(event.getQuantity());
        entity.setPrice(event.getPrice());
        entity.setSide(order.getSide());
        entity.setStrategyId(order.getStrategyId());
        entity.setExecutedAt(java.time.Instant.now());
        entity.setRealizedPnl(java.math.BigDecimal.ZERO); // В реальной системе здесь был бы расчет PnL
        
        TradeEntity saved = tradeRepository.save(entity);

        // Обновляем состояние риск-движка
        riskStateStore.update(new com.tradingbot.domain.risk.RiskEvent.TradeExecuted(
                saved.getExchangeTradeId(),
                saved.getSymbol(),
                saved.getQuantity(),
                saved.getPrice(),
                saved.getRealizedPnl(),
                saved.getExecutedAt()
        ));
        
        eventPublisher.publishEvent(new TradeCreatedEvent(                saved.getId(),
                saved.getOrderId(),
                saved.getSymbol(),
                saved.getStrategyId(),
                saved.getQuantity(),
                saved.getPrice(),
                saved.getSide()
        ));
    }
    public List<Trade> getTradeHistory(String symbol, String strategyId) {
        return tradeRepository.findBySymbolAndStrategyIdOrderByExecutedAtAsc(symbol, strategyId)
                .stream()
                .map(tradeMapper::toDomain)
                .toList();
    }

    public List<Trade> getAllTrades() {
        return tradeRepository.findAllByOrderByExecutedAtAsc()
                .stream()
                .map(tradeMapper::toDomain)
                .toList();
    }
}
