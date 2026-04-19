package com.tradingbot.common.enums;

/**
 * Order lifecycle states.
 *
 * FSM transitions (see OrderStateMachine):
 *   NEW → ACCEPTED | REJECTED
 *   ACCEPTED → APPROVED (risk approved, quantity sized)
 *   APPROVED → PENDING_EXECUTION (outbox written)
 *   PENDING_EXECUTION → EXECUTING (distributed lock claimed)
 *   EXECUTING → FILLED | REJECTED | ERROR
 */
public enum OrderStatus {
    NEW,                // Created in system, before risk check
    ACCEPTED,           // Passed risk gate (FSM: RISK_CHECK_PASSED)
    APPROVED,           // Risk-sized ApprovedOrder built, outbox entry pending
    PENDING_EXECUTION,  // Outbox committed, ready for exchange dispatch
    EXECUTING,          // Distributed lock claimed, in-flight to exchange
    FILLED,             // Fully executed on exchange
    REJECTED,           // Rejected by risk engine or exchange
    ERROR               // Critical error during processing
}