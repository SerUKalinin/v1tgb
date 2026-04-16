package com.tradingbot.infrastructure.persistence;

import com.tradingbot.domain.model.SignalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SignalRepository extends JpaRepository<SignalEntity, Long> {
}
