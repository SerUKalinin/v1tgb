package com.tradingbot.infrastructure.binance;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.ExecutionResult;
import lombok.experimental.UtilityClass;

import java.util.UUID;

/**
 * Единый маппер статусов Binance → ExecutionResult.Status → OrderStatus → Action.
 *
 * <p>Консолидирует всю логику, ранее разбросанную по:
 * <ul>
 *   <li>BinanceExecutionAdapter.mapBinanceStatus()</li>
 *   <li>BinanceExecutionAdapter.isTerminalBinanceStatus()</li>
 *   <li>BinanceExecutionAdapter.mapErrorToResult()</li>
 *   <li>OrderStateTransitionPolicy.mapExecutionResult()</li>
 *   <li>OrderExecutionHandler.commitExecution() (switch по статусам)</li>
 *   <li>ReconciliationService.syncOrderWithExchange() (switch по статусам)</li>
 * </ul>
 *
 * <h2>Маппинг Binance → ExecutionResult.Status</h2>
 * <pre>
 * FILLED           → FILLED
 * PARTIALLY_FILLED → PARTIALLY_FILLED
 * NEW, ACCEPTED    → ACCEPTED
 * CANCELED, EXPIRED, PENDING_CANCEL → CANCELED
 * REJECTED         → REJECTED
 * всё остальное    → EXCHANGE_STATE_UNKNOWN
 * </pre>
 *
 * <h2>Маппинг ExecutionResult.Status → Action</h2>
 * <pre>
 * FILLED                 → FILL          (order.fill)
 * FILLED (recon)         → FORCE_FILL    (order.forceFill)
 * PARTIALLY_FILLED       → PARTIALLY_FILL (order.applyPartialFill)
 * ACCEPTED               → MARK_ACCEPTED (order.markAccepted)
 * REJECTED               → REJECT        (order.markAsRejected + riskEngine.release)
 * CANCELED               → CANCEL        (order.markCancelled + riskEngine.release)
 * EXCHANGE_STATE_UNKNOWN → MARK_UNKNOWN  (order.markAsUnknown)
 * </pre>
 *
 * <h2>Маппинг ExecutionResult.Status → OrderStatus</h2>
 * <pre>
 * FILLED                 → FILLED
 * PARTIALLY_FILLED       → PARTIALLY_FILLED
 * ACCEPTED               → SENT_TO_EXCHANGE
 * REJECTED               → REJECTED
 * CANCELED               → CANCELED
 * EXCHANGE_STATE_UNKNOWN → UNKNOWN
 * </pre>
 */
@UtilityClass
public class BinanceStatusMapper {

    /**
     * Действие, которое потребитель должен выполнить с Order
     * на основе ExecutionResult.Status.
     */
    public enum Action {
        /** order.fill(context, exchangeOrderId, executedQty, executedPrice) */
        FILL,

        /** order.forceFill(context, exchangeOrderId, executedQty, executedPrice) — для reconciliation */
        FORCE_FILL,

        /** order.applyPartialFill(context, executedQty, executedPrice) */
        PARTIALLY_FILL,

        /** order.markAccepted(context, exchangeOrderId) */
        MARK_ACCEPTED,

        /** order.markAsRejected(context, reason) + riskEngine.release(context) */
        REJECT,

        /** order.markCancelled(context) + riskEngine.release(context) */
        CANCEL,

        /** order.markAsUnknown(context) */
        MARK_UNKNOWN,

        /** Ничего не делать (ордер не изменился) */
        NOOP
    }

    // ─── Binance API status → ExecutionResult.Status ───────────────────────────

    /**
     * Преобразует статус ордера Binance API в внутренний статус исполнения.
     *
     * @param binanceStatus статус, полученный от Binance API
     * @return внутренний статус ExecutionResult.Status
     */
    public static ExecutionResult.Status mapBinanceStatus(String binanceStatus) {
        return switch (binanceStatus) {
            case "FILLED"            -> ExecutionResult.Status.FILLED;
            case "PARTIALLY_FILLED" -> ExecutionResult.Status.PARTIALLY_FILLED;
            case "NEW", "ACCEPTED"  -> ExecutionResult.Status.ACCEPTED;
            case "CANCELED", "EXPIRED", "PENDING_CANCEL" -> ExecutionResult.Status.CANCELED;
            case "REJECTED"         -> ExecutionResult.Status.REJECTED;
            default                 -> ExecutionResult.Status.EXCHANGE_STATE_UNKNOWN;
        };
    }

    // ─── Терминальные статусы Binance ──────────────────────────────────────────

    /**
     * Проверяет, является ли статус Binance финальным (терминальным).
     *
     * @param binanceStatus статус Binance
     * @return true, если статус завершает жизненный цикл ордера
     */
    public static boolean isTerminalBinanceStatus(String binanceStatus) {
        return "FILLED".equals(binanceStatus)
                || "CANCELED".equals(binanceStatus)
                || "REJECTED".equals(binanceStatus)
                || "EXPIRED".equals(binanceStatus);
    }

    // ─── ExecutionResult.Status → Action (execution path) ──────────────────────

    /**
     * Маппинг статуса исполнения в действие над доменной моделью ордера
     * в основном execution-пайплайне.
     *
     * @param status внутренний статус исполнения
     * @return действие над Order
     */
    public static Action mapToAction(ExecutionResult.Status status) {
        return switch (status) {
            case FILLED                -> Action.FILL;
            case PARTIALLY_FILLED      -> Action.PARTIALLY_FILL;
            case ACCEPTED              -> Action.MARK_ACCEPTED;
            case REJECTED              -> Action.REJECT;
            case CANCELED              -> Action.CANCEL;
            case EXCHANGE_STATE_UNKNOWN -> Action.MARK_UNKNOWN;
        };
    }

    // ─── ExecutionResult.Status → Action (reconciliation path) ─────────────────

    /**
     * Маппинг статуса исполнения в действие для reconciliation-процесса.
     *
     * @param status внутренний статус исполнения
     * @return действие для восстановления консистентности
     */
    public static Action mapToReconciliationAction(ExecutionResult.Status status) {
        return switch (status) {
            case FILLED                -> Action.FORCE_FILL;
            case PARTIALLY_FILLED      -> Action.PARTIALLY_FILL;
            case ACCEPTED              -> Action.NOOP;
            case REJECTED              -> Action.REJECT;
            case CANCELED              -> Action.CANCEL;
            case EXCHANGE_STATE_UNKNOWN -> Action.MARK_UNKNOWN;
        };
    }

    // ─── ExecutionResult.Status → OrderStatus ──────────────────────────────────

    /**
     * Преобразует статус исполнения в доменный статус ордера.
     *
     * @param status внутренний статус исполнения
     * @return OrderStatus доменной модели
     */
    public static OrderStatus mapToOrderStatus(ExecutionResult.Status status) {
        return switch (status) {
            case FILLED                -> OrderStatus.FILLED;
            case PARTIALLY_FILLED      -> OrderStatus.PARTIALLY_FILLED;
            case ACCEPTED              -> OrderStatus.SENT_TO_EXCHANGE;
            case REJECTED              -> OrderStatus.REJECTED;
            case CANCELED              -> OrderStatus.CANCELED;
            case EXCHANGE_STATE_UNKNOWN -> OrderStatus.UNKNOWN;
        };
    }

    // ─── Exception → ExecutionResult ───────────────────────────────────────────

    /**
     * Преобразует исключение при вызове биржи в структурированный результат исполнения.
     *
     * @param e исключение
     * @param orderId идентификатор ордера
     * @return ExecutionResult с типом ошибки
     */
    public static ExecutionResult mapError(Exception e, UUID orderId) {
        String msg = e.getMessage();
        if (msg != null && (msg.contains("400") || msg.contains("-1013") || msg.contains("-1111"))) {
            return ExecutionResult.rejected(orderId, msg);
        }
        if (msg != null && (msg.contains("Timeout") || msg.contains("504"))) {
            return ExecutionResult.exchangeStateUnknown(orderId);
        }
        return ExecutionResult.failedIo(orderId, msg);
    }
}