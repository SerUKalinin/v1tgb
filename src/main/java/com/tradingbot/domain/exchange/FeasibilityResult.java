package com.tradingbot.domain.exchange;

import lombok.Value;
import java.math.BigDecimal;

/**
 * Результат проверки возможности исполнения сделки на бирже.
 * <p>
 * Используется для определения, может ли ордер быть отправлен на биржу
 * с учётом текущих ограничений (баланс, лимиты, правила риск-менеджмента).
 * Содержит признак допустимости операции и причину отказа при необходимости.
 */
@Value
public class FeasibilityResult {

    /**
     * Признак возможности исполнения операции:
     * true — операция допустима,
     * false — операция отклонена.
     */
    boolean feasible;

    /**
     * Причина отказа в исполнении операции.
     * Заполняется только если feasible = false.
     */
    String reason;

    /**
     * Создаёт результат успешной проверки (операция допустима).
     *
     * @return объект результата с feasible = true
     */
    public static FeasibilityResult success() {
        return new FeasibilityResult(true, null);
    }

    /**
     * Создаёт результат отклонённой проверки.
     *
     * @param reason причина, по которой операция недопустима
     * @return объект результата с feasible = false
     */
    public static FeasibilityResult rejected(String reason) {
        return new FeasibilityResult(false, reason);
    }
}