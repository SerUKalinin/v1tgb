package com.tradingbot.interfaces.web;

import com.tradingbot.domain.model.SubscriptionTier;
import com.tradingbot.domain.risk.RiskEngine;
import com.tradingbot.infrastructure.persistence.repository.SignalRepository;
import com.tradingbot.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/debug/saas")
@RequiredArgsConstructor
public class SaasMonitoringController {
    private final SignalRepository signalRepository;
    private final RiskEngine riskEngine;
    private final UserRepository userRepository;

    @GetMapping
    public Map<String, Object> getStatus() {
        return Map.of(
            "users", userRepository.findAll(),
            "risk_state", riskEngine.getState(),
            "recent_signals", signalRepository.findAll().stream().limit(10).toList()
        );
    }

    @GetMapping("/upgrade")
    public String upgradeUser() {
        // Удаляем тестовых пользователей, которые спамят в логи ошибками
        List<Long> testIds = List.of(1L, 111L, 999L);
        testIds.forEach(id -> userRepository.findByChatId(id).ifPresent(userRepository::delete));

        // Апгрейдим ваш реальный ID
        userRepository.findByChatId(403753468L).ifPresent(user -> {
            user.setTier(SubscriptionTier.PRO);
            userRepository.save(user);
        });
        
        return "Test users removed. User 403753468 upgraded to PRO. Now you will see real prices!";
    }
}
