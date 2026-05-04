package com.tradingbot.infrastructure.execution.binance;

import com.tradingbot.domain.exchange.*;
import com.tradingbot.infrastructure.execution.exchange.ExchangeMetadataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceFeasibilityValidator implements ExchangeFeasibilityPort, OrderNormalizationService {

    private final ExchangeMetadataService metadataService;

    @Override
    public NormalizedOrder normalize(FeasibilityRequest request) {
        var constraintsOpt = metadataService.getConstraints(request.getSymbol());
        if (constraintsOpt.isEmpty()) {
            return new NormalizedOrder(request.getSymbol(), request.getQuantity(), request.getPrice());
        }

        SymbolConstraints limits = constraintsOpt.get();
        BigDecimal adjustedQty = floorToStep(request.getQuantity(), limits.getStepSize());
        BigDecimal adjustedPrice = request.getPrice();
        if (limits.getTickSize() != null && adjustedPrice != null && adjustedPrice.compareTo(BigDecimal.ZERO) > 0) {
            adjustedPrice = roundToTick(adjustedPrice, limits.getTickSize());
        }

        return new NormalizedOrder(request.getSymbol(), adjustedQty, adjustedPrice);
    }

    @Override
    public FeasibilityResult check(FeasibilityRequest request) {
        var constraintsOpt = metadataService.getConstraints(request.getSymbol());
        if (constraintsOpt.isEmpty()) {
            return FeasibilityResult.rejected("METADATA_MISSING");
        }

        SymbolConstraints limits = constraintsOpt.get();

        // Проверка minQty
        if (request.getQuantity().compareTo(limits.getMinQty()) < 0) {
            return FeasibilityResult.rejected("QTY_BELOW_MIN: " + request.getQuantity() + " < " + limits.getMinQty());
        }

        // Проверка minNotional
        if (limits.getMinNotional() != null && request.getPrice() != null) {
            BigDecimal notional = request.getQuantity().multiply(request.getPrice());
            if (notional.compareTo(limits.getMinNotional()) < 0) {
                return FeasibilityResult.rejected("NOTIONAL_BELOW_MIN: " + notional + " < " + limits.getMinNotional());
            }
        }

        return FeasibilityResult.success();
    }

    private BigDecimal floorToStep(BigDecimal value, BigDecimal stepSize) {
        if (stepSize == null || stepSize.compareTo(BigDecimal.ZERO) <= 0) return value;
        BigDecimal remainder = value.remainder(stepSize);
        return value.subtract(remainder).setScale(stepSize.scale(), RoundingMode.FLOOR);
    }

    private BigDecimal roundToTick(BigDecimal value, BigDecimal tickSize) {
        if (tickSize == null || tickSize.compareTo(BigDecimal.ZERO) <= 0) return value;
        BigDecimal steps = value.divide(tickSize, 0, RoundingMode.HALF_UP);
        return steps.multiply(tickSize).setScale(tickSize.scale(), RoundingMode.HALF_UP);
    }
}
