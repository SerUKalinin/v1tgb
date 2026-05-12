package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.tracing.BusinessContext;
import com.tradingbot.tracing.ExecutionAttemptContext;
import com.tradingbot.tracing.IdentityContext;
import com.tradingbot.domain.execution.AlreadyClaimedException;
import com.tradingbot.domain.execution.ExecutionClaimPort;
import com.tradingbot.infrastructure.persistence.entity.ExecutionClaimEntity;
import com.tradingbot.infrastructure.persistence.repository.ExecutionClaimRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import static jakarta.transaction.Transactional.TxType.REQUIRES_NEW;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ExecutionClaimAdapter implements ExecutionClaimPort {

    private final ExecutionClaimRepository repository;

    @Override
    @Transactional(REQUIRES_NEW)
    public void claim(IdentityContext identity, ExecutionAttemptContext attempt, BusinessContext business) {
        UUID signalId = identity.signalId();
        UUID executionId = attempt.executionId();

        ExecutionClaimEntity entity = ExecutionClaimEntity.builder()
                .signalId(signalId)
                .executionId(executionId)
                .status(ExecutionClaimEntity.STATUS_CLAIMED)
                .claimedAt(Instant.now())
                .build();

        try {
            repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            if (isSignalAlreadyClaimed(e)) {
                throw new AlreadyClaimedException("Signal " + signalId + " already claimed by another execution", e);
            }
            throw e;
        }
    }    private boolean isSignalAlreadyClaimed(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        if (cause == null || cause.getMessage() == null) {
            return false;
        }
        String message = cause.getMessage().toLowerCase();
        return message.contains("uq_execution_claims_signal_id")
                || message.contains("execution_claims_signal_id")
                || message.contains("unique") && message.contains("signal_id");
    }
}
