package com.tradingbot.infrastructure.persistence.repository;

import com.tradingbot.infrastructure.persistence.entity.RiskReservationLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;
import java.util.List;

@Repository
public interface RiskReservationLogRepository extends JpaRepository<RiskReservationLogEntity, UUID> {
    List<RiskReservationLogEntity> findByOrderId(UUID orderId);
    List<RiskReservationLogEntity> findAllByOrderBySequenceIdAsc();
}
