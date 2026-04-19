package com.tradingbot.infrastructure.persistence.repository;

import com.tradingbot.domain.event.RiskEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RiskEventRepository extends JpaRepository<RiskEventEntity, Long> {

    boolean existsByEventId(UUID eventId);

    // ИЗМЕНЕНО: aggregateId String → Long
    List<RiskEventEntity> findByAggregateIdAndVersionGreaterThanOrderByVersionAsc(
            Long aggregateId, Long version);

    @Query("SELECT MAX(e.version) FROM RiskEventEntity e WHERE e.aggregateId = :aggregateId")
    Optional<Long> findMaxVersionByAggregateId(Long aggregateId);
}