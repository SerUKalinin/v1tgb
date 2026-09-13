package com.tradingbot.domain.risk;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.List;

/**
 * Результат проверки риск-менеджера.
 * <p>
 * Инкапсулирует решение о допустимости исполнения торгового запроса,
 * включая причину, объём разрешённой позиции и диагностический trace.
 */
@Value
@Builder(toBuilder = true)
public class RiskDecision {

    /**
     * Тип решения риск-менеджера.
     */
    DecisionType type;

    /**
     * Разрешённый объём (может быть уменьшен при REDUCE_SIZE).
     */
    BigDecimal amount;

    /**
     * Причина принятого решения.
     */
    Reason reason;

    /**
     * Человекочитаемое описание решения.
     */
    String message;

    /**
     * Трассировочная информация для отладки и аудита.
     */
    List<String> trace;

    /**
     * Тип решения риск-менеджера.
     */
    public enum DecisionType {
        APPROVE,
        REJECT,
        REDUCE_SIZE
    }

    /**
     * Причина отклонения или ограничения сделки.
     */
    public enum Reason {
        APPROVED,
        HALTED,
        DAILY_LIMIT_EXCEEDED,
        EXPOSURE_LIMIT_EXCEEDED,
        INSUFFICIENT_CAPITAL,
        DRAWDOWN_LIMIT_EXCEEDED,
        SYSTEM_ERROR
    }

    /**
     * Создаёт положительное решение без детализации trace.
     */
    public static RiskDecision approve(BigDecimal amount) {
        return RiskDecision.builder()
                .type(DecisionType.APPROVE)
                .amount(amount)
                .reason(Reason.APPROVED)
                .message("Risk check passed")
                .trace(List.of("Legacy approve call"))
                .build();
    }

    /**
     * Создаёт отказ по умолчанию (SYSTEM_ERROR).
     */
    public static RiskDecision reject(String message) {
        return RiskDecision.builder()
                .type(DecisionType.REJECT)
                .amount(BigDecimal.ZERO)
                .reason(Reason.SYSTEM_ERROR)
                .message(message)
                .trace(List.of("Legacy reject call: " + message))
                .build();
    }

    /**
     * Создаёт положительное решение с trace.
     */
    public static RiskDecision approve(BigDecimal amount, List<String> trace) {
        return RiskDecision.builder()
                .type(DecisionType.APPROVE)
                .amount(amount)
                .reason(Reason.APPROVED)
                .message("Risk check passed")
                .trace(trace)
                .build();
    }

    /**
     * Создаёт отказ с указанной причиной и trace.
     */
    public static RiskDecision reject(Reason reason, String message, List<String> trace) {
        return RiskDecision.builder()
                .type(DecisionType.REJECT)
                .amount(BigDecimal.ZERO)
                .reason(reason)
                .message(message)
                .trace(trace)
                .build();
    }

    /**
     * Проверяет, разрешена ли сделка (APPROVE или REDUCE_SIZE).
     */
    public boolean isApproved() {
        return type == DecisionType.APPROVE || type == DecisionType.REDUCE_SIZE;
    }
}