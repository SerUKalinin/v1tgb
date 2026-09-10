package com.tradingbot.infrastructure.execution.binance;

import com.tradingbot.domain.exchange.*;
import com.tradingbot.infrastructure.execution.exchange.ExchangeMetadataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Валидатор и нормализатор параметров ордеров для Binance.
 *
 * <p>Реализует две ключевые функции:
 * <ul>
 *     <li>Проверка исполнимости ордера с учётом ограничений биржи</li>
 *     <li>Приведение параметров ордера к допустимым шагам (stepSize, tickSize)</li>
 * </ul>
 *
 * <h2>Зависимость:</h2>
 * Использует {@link ExchangeMetadataService} для получения торговых ограничений символа.
 *
 * <h2>Результат работы:</h2>
 * <ul>
 *     <li>{@link FeasibilityResult} — результат проверки исполнимости</li>
 *     <li>{@link NormalizedOrder} — скорректированный ордер под требования биржи</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceFeasibilityValidator implements ExchangeFeasibilityPort, OrderNormalizationService {

    private final ExchangeMetadataService metadataService;

    /**
     * Приводит параметры ордера к допустимым значениям биржи.
     *
     * <p>Корректирует:
     * <ul>
     *     <li>quantity → округление вниз по stepSize</li>
     *     <li>price → округление по tickSize (если задан)</li>
     * </ul>
     *
     * @param request исходный запрос
     * @return нормализованный ордер
     */
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

    /**
     * Проверяет возможность исполнения ордера на бирже.
     *
     * <p>Проверяет:
     * <ul>
     *     <li>минимальный размер ордера (minQty)</li>
     *     <li>минимальный notional (minNotional)</li>
     * </ul>
     *
     * @param request запрос на проверку
     * @return результат проверки исполнимости
     */
    @Override
    public FeasibilityResult check(FeasibilityRequest request) {
        var constraintsOpt = metadataService.getConstraints(request.getSymbol());
        if (constraintsOpt.isEmpty()) {
            return FeasibilityResult.rejected("METADATA_MISSING");
        }

        SymbolConstraints limits = constraintsOpt.get();

        // Проверка minQty
        if (request.getQuantity().compareTo(limits.getMinQty()) < 0) {
            return FeasibilityResult.rejected(
                    "QTY_BELOW_MIN: " + request.getQuantity() + " < " + limits.getMinQty()
            );
        }

        // Проверка minNotional
        if (limits.getMinNotional() != null && request.getPrice() != null) {
            BigDecimal notional = request.getQuantity().multiply(request.getPrice());
            if (notional.compareTo(limits.getMinNotional()) < 0) {
                return FeasibilityResult.rejected(
                        "NOTIONAL_BELOW_MIN: " + notional + " < " + limits.getMinNotional()
                );
            }
        }

        return FeasibilityResult.success();
    }

    /**
     * Округляет количество вниз до допустимого шага (stepSize).
     */
    private BigDecimal floorToStep(BigDecimal value, BigDecimal stepSize) {
        if (stepSize == null || stepSize.compareTo(BigDecimal.ZERO) <= 0) return value;
        BigDecimal remainder = value.remainder(stepSize);
        return value.subtract(remainder).setScale(stepSize.scale(), RoundingMode.FLOOR);
    }

    /**
     * Округляет цену до ближайшего допустимого тика (tickSize).
     */
    private BigDecimal roundToTick(BigDecimal value, BigDecimal tickSize) {
        if (tickSize == null || tickSize.compareTo(BigDecimal.ZERO) <= 0) return value;
        BigDecimal steps = value.divide(tickSize, 0, RoundingMode.HALF_UP);
        return steps.multiply(tickSize).setScale(tickSize.scale(), RoundingMode.HALF_UP);
    }
}