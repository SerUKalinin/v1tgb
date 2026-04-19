package com.tradingbot.infrastructure.persistence.repository;

import com.tradingbot.domain.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface JpaUserRepository extends JpaRepository<User, Long> {
    Optional<User> findByChatId(Long chatId);
    java.util.List<User> findByActiveTrue();
}
