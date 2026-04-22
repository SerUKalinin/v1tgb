package com.tradingbot.infrastructure.persistence.repository;

import com.tradingbot.infrastructure.persistence.entity.EquitySnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EquitySnapshotRepository extends JpaRepository<EquitySnapshotEntity, Long> {
    List<EquitySnapshotEntity> findTop10ByOrderByTimestampDesc();
}
