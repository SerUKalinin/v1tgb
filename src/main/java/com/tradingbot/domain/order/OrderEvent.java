package com.tradingbot.domain.order;

/**
 * Events that trigger FSM state transitions for orders.
 *
 * Added:
 * - RISK_SIZED       : Risk Engine approved and sized the quantity → ACCEPTED → APPROVED
 * - OUTBOX_COMMITTED : Outbox entry written transactionally → APPROVED → PENDING_EXECUTION
 */
public enum OrderEvent {
    SIGNAL_RECEIVED,      // Signal received from strategy
    RISK_CHECK_PASSED,    // Risk gate passed (initial check)
    RISK_CHECK_FAILED,    // Risk gate rejected
    RISK_SIZED,           // ApprovedOrder built with sized quantity → APPROVED
    OUTBOX_COMMITTED,     // Outbox entry written atomically → PENDING_EXECUTION
    EXECUTION_STARTED,    // Distributed lock claimed
    EXECUTION_SUCCESS,    // Exchange confirmed fill
    EXECUTION_FAILED,     // Exchange returned error
    RECOVERY_TRIGGERED,   // Watchdog recovery initiated
    EXTERNAL_SYNC         // Exchange state sync (e.g. partial fill)
}