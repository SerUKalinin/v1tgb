package com.tradingbot.infrastructure.persistence.entity;

import com.tradingbot.domain.exception.InvalidTradeDataException;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA-сущность для хранения информации о позициях в базе данных.
 */
@Entity
@Table(name = "positions",
        uniqueConstraints = @UniqueConstraint(name = "uq_positions_symbol_strategy", columnNames = {"symbol", "strategy_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class PositionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private java.util.UUID id;

    @Column(nullable = false)
    private String symbol;

    @Column(name = "strategy_id", nullable = false)
    private String strategyId;

    @Column(name = "net_quantity", nullable = false, precision = 38, scale = 18)
    private BigDecimal quantity;

    @Column(name = "avg_entry_price", nullable = false, precision = 38, scale = 18)
    private BigDecimal entryPrice;

    @Column(name = "realized_pnl", precision = 38, scale = 18)
    private BigDecimal realizedPnl;

    @Column(name = "last_trade_id")
    private java.util.UUID lastTradeId;

    @Column(name = "stop_loss", precision = 38, scale = 18)
    private BigDecimal stopLoss;

    @Column(name = "take_profit", precision = 38, scale = 18)
    private BigDecimal takeProfit;

    @Builder.Default
    @Column(nullable = false)
    private String status = "OPEN";

    @Column(name = "close_request_id")
    private java.util.UUID closeRequestId;

    @Version
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Применяет сделку к позиции, пересчитывая среднюю цену и количество.
     * Инвариант: количество не может быть отрицательным (только Long позиции).
     */
    public void applyTrade(BigDecimal tradeQty, BigDecimal tradePrice, java.util.UUID tradeId) {
        if (tradeQty == null) {
            throw new InvalidTradeDataException(symbol, "tradeQty", null);
        }
        if (tradePrice == null) {
            throw new InvalidTradeDataException(symbol, "tradePrice", null);
        }
        if (tradeQty.signum() == 0) {
            throw new InvalidTradeDataException(symbol, "tradeQty", tradeQty);
        }
        
        BigDecimal newQuantity = this.quantity.add(tradeQty);
        
        // Бизнес-инвариант: защита от отрицательного количества (Short не поддерживается в этой модели)
        if (newQuantity.signum() < 0) {
            throw new IllegalStateException("Position quantity cannot be negative for symbol: " + symbol);
        }

        // Пересчет средней цены входа (только при увеличении позиции)
        if (tradeQty.signum() > 0) {
            BigDecimal totalCost = this.quantity.multiply(this.entryPrice)
                    .add(tradeQty.multiply(tradePrice));
            this.entryPrice = totalCost.divide(newQuantity, 18, java.math.RoundingMode.HALF_UP);
        }

        this.quantity = newQuantity;
        this.lastTradeId = tradeId;
        
        if (this.quantity.signum() == 0) {
            this.status = "CLOSED";
        } else {
            this.status = "OPEN";
        }
    }

    public void updateStopLoss(BigDecimal stopLoss) {
        if (stopLoss != null && stopLoss.signum() <= 0) {
            throw new IllegalArgumentException("Stop loss must be positive");
        }
        this.stopLoss = stopLoss;
    }

    public void updateTakeProfit(BigDecimal takeProfit) {
        if (takeProfit != null && takeProfit.signum() <= 0) {
            throw new IllegalArgumentException("Take profit must be positive");
        }
        this.takeProfit = takeProfit;
    }

    public void markAsClosing(java.util.UUID requestId) {
        this.closeRequestId = requestId;
    }

    @PrePersist
    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }
}