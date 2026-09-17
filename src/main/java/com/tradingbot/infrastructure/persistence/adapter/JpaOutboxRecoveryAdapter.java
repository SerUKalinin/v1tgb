package com.tradingbot.infrastructure.persistence.adapter;

import com.tradingbot.domain.model.OutboxRecoveryPort;
import com.tradingbot.infrastructure.outbox.OutboxStatus;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JpaOutboxRecoveryAdapter implements OutboxRecoveryPort {

    private final OutboxEventRepository repository;

    @Override
    @Transactional
    public int resetStaleProcessingEvents(Instant threshold) {
        if (threshold == null) {
            throw new IllegalArgumentException(
                    "threshold cannot be null"
            );
        }

        List<OutboxEventEntity> stuckEvents =
                repository.findStaleProcessingEvents(threshold);

        if (stuckEvents.isEmpty()) {
            return 0;
        }

        Instant now = Instant.now();

        for (OutboxEventEntity event : stuckEvents) {
            event.setStatus(OutboxStatus.FAILED);
            event.setUpdatedAt(now);
        }

        repository.saveAll(stuckEvents);

        return stuckEvents.size();
    }
}