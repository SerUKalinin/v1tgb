package com.tradingbot.infrastructure.persistence.repository;

import com.tradingbot.infrastructure.persistence.entity.ExecutionClaimEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExecutionClaimRepository extends JpaRepository<ExecutionClaimEntity, UUID> {
    boolean existsBySignalId(UUID signalId);
    boolean existsByExecutionId(UUID executionId);
}
