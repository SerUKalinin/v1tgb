package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.domain.execution.SignalClaimPort;
import com.tradingbot.infrastructure.persistence.entity.SignalClaimEntity;
import com.tradingbot.infrastructure.persistence.repository.SignalClaimRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW;

@Component
@RequiredArgsConstructor
public class SignalClaimAdapter implements SignalClaimPort {

    private final SignalClaimRepository repository;

    @Override
    public boolean exists(UUID signalId) {
        return repository.existsById(signalId);
    }

    @Override
    @Transactional(propagation = REQUIRES_NEW)
    public void claim(UUID signalId) {
        try {
            repository.saveAndFlush(SignalClaimEntity.builder()
                    .signalId(signalId)
                    .build());
        } catch (DataIntegrityViolationException e) {
            // Already claimed
        }
    }
}
