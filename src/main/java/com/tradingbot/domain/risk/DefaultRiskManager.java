package com.tradingbot.domain.risk;

import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.market.ExchangeMetadataProvider;
import com.tradingbot.domain.model.Signal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Stateless Decision Engine для управления рисками.
 * Принимает сигнал и текущее состояние, возвращает решение.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultRiskManager implements RiskManager {

    private final ExchangeMetadataProvider metadataProvider;

    @Override
    public Optional<ApprovedOrder> approveSignal(Signal signal, RiskState state) {
        // 1. HALT CHECK (Kill-switch)
        if (state.isHalted()) {
            log.error("[Risk] System HALTED. Rejecting signal for {}", signal.getSymbol());
            return Optional.empty();
        }

        // 2. COOLDOWN CHECK (State-based)
        Instant cooldownUntil = state.getCooldowns().getOrDefault(signal.getSymbol(), Instant.MIN);
        if (Instant.now().isBefore(cooldownUntil)) {
            log.warn("[Risk] Cooldown active for {}. Until: {}", signal.getSymbol(), cooldownUntil);
            return Optional.empty();
        }

        // 3. POSITION SIZING (1% equity default если не указано в сигнале)
        BigDecimal quantity = signal.getQuantity() != null ? signal.getQuantity() : calculateDefaultQuantity(signal, state);

        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[Risk] Calculated quantity is zero for {}", signal.getSymbol());
            return Optional.empty();
        }

        // 4. EXPOSURE LIMITS (Symbol 20%, Total 500%)
        if (!isExposureAllowed(signal.getSymbol(), quantity, signal.getPrice(), state)) {
            log.warn("[Risk] Exposure limit reached for {}", signal.getSymbol());
            return Optional.empty();
        }

        // 5. EXCHANGE CONSTRAINTS (Lot size / Precision)
        quantity = applyExchangeConstraints(quantity, signal.getSymbol());

        // 6. BUILD APPROVED ORDER (Immutable DTO)
        BigDecimal currentExposure = state.getSymbolExposures().getOrDefault(signal.getSymbol(), BigDecimal.ZERO);
        
        ApprovedOrder approvedOrder = ApprovedOrder.builder()
                .orderId(UUID.randomUUID().toString())
                .clientOrderId(signal.getClientOrderId())
                .symbol(signal.getSymbol())
                .side(signal.getSide())
                .type(OrderType.MARKET)
                .quantity(quantity)
                .price(signal.getPrice())
                .strategyId(signal.getStrategyId())
                .approvedAt(Instant.now())
                .riskStateVersion(state.getVersion())
                .approvedExposure(currentExposure)
                .build();

        log.info("[Risk] Signal APPROVED: {} {} qty={}",
                approvedOrder.getSymbol(), approvedOrder.getSide(), approvedOrder.getQuantity());

        return Optional.of(approvedOrder);
    }

    @Override
    public boolean isApprovalFresh(ApprovedOrder order, RiskState currentState) {
        // 1. Kill-switch check (самый приоритетный)
        if (currentState.isHalted()) {
            log.error("[Risk-Validation] System HALTED. Rejecting stale order {}", order.getOrderId());
            return false;
        }

        // 2. Version check (Optimistic Locking)
        // Если версия изменилась, мы должны убедиться, что новый ордер не нарушает лимиты
        // в контексте НОВОГО состояния.
        if (order.getRiskStateVersion() != currentState.getVersion()) {
            log.warn("[Risk-Validation] Version mismatch (Order: {}, Current: {}). Re-validating exposure...", 
                    order.getRiskStateVersion(), currentState.getVersion());
            
            return isExposureAllowed(order.getSymbol(), order.getQuantity(), order.getPrice(), currentState);
        }

        return true;
    }
    private BigDecimal calculateDefaultQuantity(Signal signal, RiskState state) {
        BigDecimal riskPercent = new BigDecimal("0.01"); // 1% от капитала
        BigDecimal baseCapital = state.getTotalEquity().max(state.getBalance());

        if (baseCapital.compareTo(BigDecimal.ZERO) <= 0 || signal.getPrice() == null || signal.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        return baseCapital.multiply(riskPercent)
                .divide(signal.getPrice(), 8, RoundingMode.HALF_UP);
    }

    private boolean isExposureAllowed(String symbol, BigDecimal quantity, BigDecimal price, RiskState state) {
        BigDecimal orderValue = quantity.multiply(price);
        BigDecimal equity = state.getTotalEquity();

        if (equity.compareTo(BigDecimal.ZERO) <= 0) return false;

        // Лимит на один символ: 20% от Equity
        BigDecimal symbolExposure = state.getSymbolExposures().getOrDefault(symbol, BigDecimal.ZERO);
        BigDecimal maxSymbolValue = equity.multiply(new BigDecimal("0.20"));
        if (symbolExposure.add(orderValue).compareTo(maxSymbolValue) > 0) {
            log.warn("[Risk] Symbol exposure limit (20%) exceeded for {}", symbol);
            return false;
        }

        // Общий лимит портфеля: 500% (плечо 5x)
        BigDecimal totalExposure = state.getSymbolExposures().values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal maxTotalValue = equity.multiply(new BigDecimal("5.0"));
        if (totalExposure.add(orderValue).compareTo(maxTotalValue) > 0) {
            log.warn("[Risk] Total portfolio exposure limit (500%) exceeded");
            return false;
        }

        return true;
    }

    private BigDecimal applyExchangeConstraints(BigDecimal quantity, String symbol) {
        BigDecimal lotSize = metadataProvider.getLotSize(symbol);
        int precision = metadataProvider.getQuantityPrecision(symbol);

        if (lotSize.compareTo(BigDecimal.ZERO) <= 0) return quantity;

        return quantity
                .divide(lotSize, 0, RoundingMode.DOWN)
                .multiply(lotSize)
                .setScale(precision, RoundingMode.DOWN);
    }
}
