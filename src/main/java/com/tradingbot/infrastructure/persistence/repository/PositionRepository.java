package com.tradingbot.infrastructure.persistence.repository;

import com.tradingbot.infrastructure.persistence.entity.PositionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Репозиторий для работы с сущностью PositionEntity в базе данных.
 */
@Repository
public interface PositionRepository extends JpaRepository<PositionEntity, String> {
}