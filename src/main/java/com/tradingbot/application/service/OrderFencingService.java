package com.tradingbot.application.service;

import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Сервис для реализации механизма Fencing (лизинга) ордеров.
 * Гарантирует, что только один исполнитель работает с ордером в данный момент.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderFencingService {

    private final OrderRepository orderRepository;
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);

    /**
     * Пытается захватить право на исполнение ордера.
     * @param orderId ID ордера
     * @param ownerId Уникальный ID исполнителя (например, имя потока или инстанса)
     * @return true если захват успешен
     */
    @Transactional
    public boolean tryAcquire(String orderId, String ownerId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(LEASE_DURATION);
        
        int updatedRows = orderRepository.claimForExecution(orderId, ownerId, expiresAt, now);
        
        if (updatedRows > 0) {
            log.debug("Successfully acquired lease for order {} by owner {}", orderId, ownerId);
            return true;
        }
        
        log.warn("Failed to acquire lease for order {}. Already owned or terminal state.", orderId);
        return false;
    }

    /**
     * Продлевает аренду (lease) для текущего владельца.
     * @param orderId ID ордера
     * @param ownerId ID владельца
     * @return true если продление успешно
     */    @Transactional
    public boolean extendLease(String orderId, String ownerId) {
        Instant now = Instant.now();
        Instant newExpiresAt = now.plus(LEASE_DURATION);
        
        int updatedRows = orderRepository.extendLease(orderId, ownerId, newExpiresAt, now);
        
        if (updatedRows > 0) {
            log.debug("Successfully extended lease for order {} by owner {}", orderId, ownerId);
            return true;
        }
        
        log.error("CRITICAL: Failed to extend lease for order {}. Lease stolen or expired!", orderId);
        return false;
    }
}
