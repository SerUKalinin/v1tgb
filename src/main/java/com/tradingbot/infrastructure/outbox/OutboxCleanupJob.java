package com.tradingbot.infrastructure.outbox;

import com.tradingbot.infrastructure.persistence.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxCleanupJob {

    private final OutboxEventRepository repository;

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    @Transactional
    public void cleanup() {
        Instant threshold = Instant.now().minus(Duration.ofHours(24));

        int deleted = repository.deleteProcessedOlderThan(threshold);

        if (deleted > 0) {
            log.info("[OUTBOX-CLEANUP] Deleted {} processed events older than {}", deleted, threshold);
        }
    }
}
