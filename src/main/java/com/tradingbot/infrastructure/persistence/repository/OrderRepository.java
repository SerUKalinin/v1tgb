package com.tradingbot.infrastructure.persistence.repository;

import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {
    List<OrderEntity> findBySymbol(String symbol);
    Optional<OrderEntity> findByClientOrderId(String clientOrderId);
    boolean existsByClientOrderId(String clientOrderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("SELECT o FROM OrderEntity o WHERE o.id = :id")
    Optional<OrderEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT o FROM OrderEntity o WHERE o.status IN :statuses AND o.createdAt < :threshold")
    List<OrderEntity> findStuckOrdersInStatuses(@Param("statuses") java.util.Collection<com.tradingbot.common.enums.OrderStatus> statuses, @Param("threshold") Instant threshold);

    @Query("SELECT o FROM OrderEntity o WHERE o.status = :status AND o.createdAt < :threshold")
    List<OrderEntity> findStuckOrders(@Param("status") com.tradingbot.common.enums.OrderStatus status, @Param("threshold") Instant threshold);}