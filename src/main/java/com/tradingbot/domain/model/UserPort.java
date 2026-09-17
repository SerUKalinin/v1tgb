package com.tradingbot.domain.model;

import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary для User.
 *
 * <p>
 * Application/domain не должны зависеть от JPA Entity,
 * Repository или Mapper.
 *
 * <p>
 * Архитектурные контракты:
 * SYSTEM_CONTRACT.md
 * STATE_MACHINE_CONTRACT.md
 * EXECUTION_ENGINE_CONTRACT.md
 */
public interface UserPort {

    Optional<User> findByChatId(Long chatId);

    List<User> findAll();

    User save(User user);
}