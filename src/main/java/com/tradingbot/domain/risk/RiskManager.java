package com.tradingbot.domain.risk;

import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.model.Order;
import com.tradingbot.tracing.ExecutionContext;

import java.util.Optional;

/**
 * Основной контракт риск-менеджера торговой системы.
 * <p>
 * Отвечает за:
 * <ul>
 *   <li>оценку входящих торговых сигналов</li>
 *   <li>проверку ордеров перед исполнением</li>
 *   <li>резервирование капитала</li>
 *   <li>валидацию "свежести" одобрения</li>
 * </ul>
 */
public interface RiskManager {

    /**
     * Выполняет полный цикл риск-оценки сигнала с резервированием капитала.
     *
     * @param context execution context (идентичность, трассировка)
     * @param signal торговый сигнал
     * @return созданный и одобренный ордер (если прошёл риск) либо empty
     */
    Optional<Order> evaluateAndReserve(ExecutionContext context, SignalEvent signal);

    /**
     * Упрощённое одобрение сигнала без резервирования капитала.
     *
     * @param signal торговый сигнал
     * @return созданный ордер, если сигнал одобрен
     */
    Optional<Order> approveSignal(SignalEvent signal);

    /**
     * Выполняет риск-проверку уже сформированного ордера.
     *
     * @param order ордер для проверки
     * @return результат риск-оценки
     */
    RiskDecision check(Order order);

    /**
     * Выполняет риск-оценку торгового сигнала в доменной форме.
     *
     * @param signal доменный сигнал
     * @return результат риск-оценки
     */
    RiskDecision evaluate(com.tradingbot.domain.model.Signal signal);

    /**
     * Проверяет, является ли ранее полученное одобрение актуальным.
     *
     * @param order ордер
     * @return true, если риск-решение ещё не устарело
     */
    boolean isApprovalFresh(Order order);
}