package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.domain.execution.AlreadyClaimedException;
import com.tradingbot.domain.execution.ExecutionClaimPort;
import com.tradingbot.infrastructure.persistence.entity.ExecutionClaimEntity;
import com.tradingbot.infrastructure.persistence.repository.ExecutionClaimRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import static jakarta.transaction.Transactional.TxType.REQUIRES_NEW;

@Component
@RequiredArgsConstructor
public class ExecutionClaimAdapter implements ExecutionClaimPort {

    private final ExecutionClaimRepository repository;

    @Override
    @Transactional(REQUIRES_NEW)
    public void claim(String signalId) {
        ExecutionClaimEntity entity = ExecutionClaimEntity.builder()
                .signalId(signalId)
                .status(ExecutionClaimEntity.STATUS_CLAIMED)
                .build();

        try {
            repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            if (isSignalAlreadyClaimed(e)) {
                throw new AlreadyClaimedException("Signal " + signalId + " already claimed", e);
            }
            throw e;
        }
    }

    private boolean isSignalAlreadyClaimed(DataIntegrityViolationException e) {
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
