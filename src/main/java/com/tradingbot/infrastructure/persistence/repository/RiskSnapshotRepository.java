package com.tradingbot.infrastructure.persistence.repository;

import com.tradingbot.infrastructure.persistence.entity.RiskSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RiskSnapshotRepository extends JpaRepository<RiskSnapshotEntity, Long> {
    Optional<RiskSnapshotEntity> findFirstByAggregateIdOrderByLastVersionDesc(String aggregateId);
}
