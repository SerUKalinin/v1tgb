package com.tradingbot.domain.risk;

import com.tradingbot.domain.model.Signal;

import java.util.Optional;

/**
 * RiskManager — the single gatekeeper for all orders.
 *
 * Implementations must be:
 * - Stateless (no fields mutated during approveSignal)
 * - Deterministic (same inputs = same output)
 * - Side-effect free (no DB writes, no HTTP calls)
 */
public interface RiskManager {

    /**
     * Evaluate a signal against current risk state.
     * Returns ApprovedOrder with sized quantity, or empty if rejected.
     */
    Optional<ApprovedOrder> approveSignal(Signal signal, RiskState state);

    /**
     * Check that the approval is still valid against the current state.
     * An approval goes stale if the risk state version changed since approval
     * (e.g. a halt was issued, or exposure limits changed).
     *
     * @param order         the order approved at a previous risk state version
     * @param currentState  the current risk state at time of execution
     */
    boolean isApprovalFresh(ApprovedOrder order, RiskState currentState);
}