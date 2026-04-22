package com.tradingbot.infrastructure.persistence.repository;

import com.tradingbot.infrastructure.persistence.entity.RiskStateSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RiskStateSnapshotRepository extends JpaRepository<RiskStateSnapshotEntity, Long> {
    @Query("SELECT s FROM RiskStateSnapshotEntity s ORDER BY s.createdAt DESC LIMIT 1")
    Optional<RiskStateSnapshotEntity> findLatest();}
