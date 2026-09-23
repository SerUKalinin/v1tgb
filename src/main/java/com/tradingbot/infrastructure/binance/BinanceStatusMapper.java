package com.tradingbot.infrastructure.binance;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.ExecutionResult;
import lombok.experimental.UtilityClass;

import java.util.UUID;

/**
 * Единый маппер Binance → ExecutionResult → reconciliation action.
 */
@UtilityClass
public class BinanceStatusMapper {

    public enum Action {

        FILL,

        FORCE_FILL,

        PARTIALLY_FILL,

        MARK_ACCEPTED,

        REJECT,

        CANCEL,

        MARK_UNKNOWN,

        NOOP
    }

    /**
     * Binance API status -> ExecutionResult.Status.
     */
    public static ExecutionResult.Status mapBinanceStatus(
            String binanceStatus
    ) {
        if (binanceStatus == null) {
            return ExecutionResult.Status.EXCHANGE_STATE_UNKNOWN;
        }

        return switch (binanceStatus) {

            case "FILLED" ->
                    ExecutionResult.Status.FILLED;

            case "PARTIALLY_FILLED" ->
                    ExecutionResult.Status.PARTIALLY_FILLED;

            case "NEW", "ACCEPTED" ->
                    ExecutionResult.Status.ACCEPTED;

            case "CANCELED",
                 "EXPIRED",
                 "PENDING_CANCEL" ->
                    ExecutionResult.Status.CANCELED;

            case "REJECTED" ->
                    ExecutionResult.Status.REJECTED;

            default ->
                    ExecutionResult.Status.EXCHANGE_STATE_UNKNOWN;
        };
    }

    public static boolean isTerminalBinanceStatus(
            String binanceStatus
    ) {
        return "FILLED".equals(binanceStatus)
                || "CANCELED".equals(binanceStatus)
                || "REJECTED".equals(binanceStatus)
                || "EXPIRED".equals(binanceStatus);
    }

    /**
     * Execution path.
     */
    public static Action mapToAction(
            ExecutionResult.Status status
    ) {
        return switch (status) {

            case FILLED ->
                    Action.FILL;

            case PARTIALLY_FILLED ->
                    Action.PARTIALLY_FILL;

            case ACCEPTED ->
                    Action.MARK_ACCEPTED;

            case REJECTED ->
                    Action.REJECT;

            case CANCELED ->
                    Action.CANCEL;

            case EXCHANGE_STATE_UNKNOWN ->
                    Action.MARK_UNKNOWN;
        };
    }

    /**
     * Reconciliation path.
     *
     * IMPORTANT:
     * ACCEPTED must NOT be NOOP while in RECOVERING.
     * The recovery result is authoritative and must move
     * RECOVERING -> SENT_TO_EXCHANGE.
     */
    public static Action mapToReconciliationAction(
            ExecutionResult.Status status
    ) {
        return switch (status) {

            case FILLED ->
                    Action.FORCE_FILL;

            case PARTIALLY_FILLED ->
                    Action.PARTIALLY_FILL;

            case ACCEPTED ->
                    Action.MARK_ACCEPTED;

            case REJECTED ->
                    Action.REJECT;

            case CANCELED ->
                    Action.CANCEL;

            case EXCHANGE_STATE_UNKNOWN ->
                    Action.MARK_UNKNOWN;
        };
    }

    public static OrderStatus mapToOrderStatus(
            ExecutionResult.Status status
    ) {
        return switch (status) {

            case FILLED ->
                    OrderStatus.FILLED;

            case PARTIALLY_FILLED ->
                    OrderStatus.PARTIALLY_FILLED;

            case ACCEPTED ->
                    OrderStatus.SENT_TO_EXCHANGE;

            case REJECTED ->
                    OrderStatus.REJECTED;

            case CANCELED ->
                    OrderStatus.CANCELED;

            case EXCHANGE_STATE_UNKNOWN ->
                    OrderStatus.UNKNOWN;
        };
    }

    public static ExecutionResult mapError(
            Exception e,
            UUID orderId
    ) {
        String msg =
                e == null
                        ? null
                        : e.getMessage();

        if (msg != null
                && (
                msg.contains("400")
                        || msg.contains("-1013")
                        || msg.contains("-1111")
        )) {

            return ExecutionResult.rejected(
                    orderId,
                    msg
            );
        }

        if (msg != null
                && (
                msg.contains("Timeout")
                        || msg.contains("504")
        )) {

            return ExecutionResult.exchangeStateUnknown(
                    orderId
            );
        }

        return ExecutionResult.failedIo(
                orderId,
                msg
        );
    }
}