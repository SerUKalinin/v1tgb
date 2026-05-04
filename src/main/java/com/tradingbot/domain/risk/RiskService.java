package com.tradingbot.domain.risk;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.event.SignalEvent;
import com.tradingbot.domain.exchange.ExchangeFeasibilityPort;
import com.tradingbot.domain.exchange.FeasibilityRequest;
import com.tradingbot.domain.exchange.FeasibilityResult;
import com.tradingbot.domain.exchange.NormalizedOrder;
import com.tradingbot.domain.exchange.OrderNormalizationService;
import com.tradingbot.domain.model.Order;
import com.tradingbot.domain.model.Signal;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.common.enums.SignalType;
import com.tradingbot.common.util.ClientOrderIdGenerator;
import com.tradingbot.common.util.MoneyMath;
import com.tradingbot.infrastructure.persistence.entity.RiskReservationLogEntity;
import com.tradingbot.infrastructure.persistence.repository.RiskReservationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Оркестратор управления рисками.
 * Отвечает за транзакции, идемпотентность и синхронизацию кэша.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskService {

    private final RiskRepository riskRepository;
    private final RiskStateReducer reducer;
    private final RiskStateStore riskStateStore;
    private final RiskReservationLogRepository riskReservationLogRepository;
    private final ExchangeFeasibilityPort feasibilityPort;
    private final OrderNormalizationService normalizationService;

    /**
     * Единственная точка входа для одобрения сигнала.
     * Обеспечивает SELECT FOR UPDATE -> Decision -> Reserve -> Commit.
     */
    @Transactional
    public Optional<Order> evaluateAndReserve(SignalEvent signal) {
        // 1. SELECT FOR UPDATE (через JpaRiskRepository.get())
        RiskState state = riskRepository.get();

        if (state.isHalted()) {
            log.error("[RISK] System is HALTED. Rejecting signal for {}", signal.getSymbol());
            return Optional.empty();
        }

        // 2. Расчет объема (Decision Logic)
        BigDecimal rawQuantity = calculateQuantity(signal, state);

        // 3. Нормализация и проверка лимитов биржи (Feasibility)
        NormalizedOrder normalized = normalizationService.normalize(new FeasibilityRequest(
                signal.getSymbol(),
                rawQuantity,
                signal.getPrice()
        ));

        FeasibilityResult feasibility = feasibilityPort.check(new FeasibilityRequest(
                normalized.getSymbol(),
                normalized.getQuantity(),
                normalized.getPrice()
        ));

        if (!feasibility.isFeasible()) {
            log.warn("[RISK] Signal rejected by exchange constraints: {} - {}",
                    signal.getSymbol(), feasibility.getReason());
            return Optional.empty();
        }

        // 4. Проверка достаточности капитала (Risk Policy)
        BigDecimal requiredCapital = normalized.getQuantity().multiply(normalized.getPrice());
        RiskDecision decision = RiskPolicy.canReserve(state, UUID.randomUUID(), requiredCapital);

        if (!decision.isApproved()) {
            log.warn("[RISK] Signal rejected by policy: {}", decision.getReason());
            return Optional.empty();
        }

        // 5. Резервирование (Atomic Update)
        UUID orderId = UUID.randomUUID();
        RiskEvent.CapitalReserved event = new RiskEvent.CapitalReserved(
                "RESERVE-" + orderId,
                orderId,
                requiredCapital
        );

        // Применяем изменения и сохраняем в той же транзакции
        RiskState newState = reducer.reduce(state, event);
        riskRepository.markEventProcessed(parseEventId(event.getEventId()), newState, event);
        logReservation(orderId, "RESERVE", requiredCapital);

        // 6. Создание Order в статусе PENDING_EXECUTION
        Order order = Order.builder()
                .id(orderId)
                .clientOrderId(ClientOrderIdGenerator.generate(orderId))
                .symbol(signal.getSymbol())
                .side(signal.getType() == SignalType.BUY ? OrderSide.BUY : OrderSide.SELL)
                .type(OrderType.MARKET)
                .originalQuantity(normalized.getQuantity())
                .price(normalized.getPrice())
                .strategyId(signal.getStrategyId())
                .status(OrderStatus.PENDING_EXECUTION)
                .build();

        syncCacheAfterCommit(newState);

        log.info("[RISK] Signal APPROVED & CAPITAL RESERVED: {} qty={} (version={})",
                order.getSymbol(), order.getQuantity(), newState.getVersion());

        return Optional.of(order);    }

    private BigDecimal calculateQuantity(SignalEvent signal, RiskState state) {
        BigDecimal riskPercent = new BigDecimal("0.01");
        BigDecimal baseCapital = state.getTotalEquity().max(state.getBalance());

        if (baseCapital.compareTo(BigDecimal.ZERO) <= 0 || signal.getPrice() == null || signal.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return new BigDecimal("0.001");
        }

        BigDecimal quantity = baseCapital.multiply(riskPercent).divide(signal.getPrice(), 8, java.math.RoundingMode.HALF_UP);
        BigDecimal minQty = new BigDecimal("0.001");
        return quantity.compareTo(minQty) < 0 ? minQty : quantity;
    }

    @Transactional
    public void publish(RiskEvent event) {
        RiskState state = riskRepository.get();
        if (state.isHalted() && !(event instanceof RiskEvent.TradingHalted)) {
            log.warn("[RISK] Engine HALTED. Event ignored: {}", event.getEventId());
            return;
        }

        UUID eventId = parseEventId(event.getEventId());

        if (riskRepository.isEventProcessed(eventId)) {
            log.info("[RISK] Duplicate event skipped: {}", event.getEventId());
            return;
        }

        try {
            BigDecimal reservedAmountBefore = null;
            if (event instanceof RiskEvent.CapitalReleased e) {
                reservedAmountBefore = state.getActiveReservations().get(e.orderId());
            }

            RiskState newState = reducer.reduce(state, event);
            riskRepository.markEventProcessed(eventId, newState, event);

            if (event instanceof RiskEvent.CapitalReserved e) {
                logReservation(e.orderId(), "RESERVE", e.amount());
            } else if (event instanceof RiskEvent.CapitalReleased e) {
                if (reservedAmountBefore != null) {
                    logReservation(e.orderId(), "RELEASE", reservedAmountBefore);
                }
            }

            syncCacheAfterCommit(newState);
        } catch (Exception e) {
            log.error("[RISK] Processing failed for event {}", event.getEventId(), e);
            throw new RuntimeException("Risk processing failed", e);
        }
    }

    private void logReservation(UUID orderId, String type, BigDecimal amount) {
        riskReservationLogRepository.save(RiskReservationLogEntity.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .eventType(type)
                .amount(amount)
                .createdAt(Instant.now())
                .build());
    }

    @Transactional
    public RiskDecision reserve(UUID orderId, BigDecimal amount) {
        RiskState state = riskRepository.get();
        RiskDecision decision = RiskPolicy.canReserve(state, orderId, amount);

        if (decision.isApproved()) {
            RiskEvent.CapitalReserved event = new RiskEvent.CapitalReserved(
                    "RESERVE-" + orderId.toString(),
                    orderId,
                    amount
            );

            RiskState newState = reducer.reduce(state, event);
            riskRepository.markEventProcessed(parseEventId(event.getEventId()), newState, event);
            logReservation(orderId, "RESERVE", amount);
            syncCacheAfterCommit(newState);
        }

        return decision;
    }

    @Transactional
    public void release(UUID orderId, BigDecimal amount, String reason) {
        RiskEvent.CapitalReleased event = new RiskEvent.CapitalReleased(
                "RELEASE-" + orderId.toString(),
                orderId,
                amount,
                reason
        );

        RiskState state = riskRepository.get();
        RiskState newState = reducer.reduce(state, event);
        riskRepository.markEventProcessed(parseEventId(event.getEventId()), newState, event);

        BigDecimal reservedAmount = state.getActiveReservations().get(orderId);
        if (reservedAmount != null) {
            logReservation(orderId, "RELEASE", reservedAmount);
        }

        syncCacheAfterCommit(newState);
    }

    @Transactional
    public void release(UUID orderId) {
        release(orderId, BigDecimal.ZERO, "COMPENSATION");
    }

    @Transactional
    public void syncBalance(BigDecimal actualBalance) {
        RiskState state = riskRepository.get();
        RiskState newState = state.toBuilder()
                .balance(MoneyMath.scale(actualBalance))
                .totalEquity(MoneyMath.add(actualBalance, state.getReservedMargin()))
                .build();

        riskRepository.save(newState);
        syncCacheAfterCommit(newState);
        log.info("[RISK] Balance synced: {}", actualBalance);
    }

    @Transactional
    public void emergencyStop(String reason) {
        RiskState state = riskRepository.get();
        RiskState newState = state.toBuilder().halted(true).build();
        riskRepository.save(newState);
        syncCacheAfterCommit(newState);
        log.error("[RISK] EMERGENCY STOP: {}", reason);
    }

    @Transactional
    public void resumeTrading() {
        RiskState state = riskRepository.get();
        RiskState newState = state.toBuilder().halted(false).build();
        riskRepository.save(newState);
        syncCacheAfterCommit(newState);
        log.info("[RISK] Trading resumed");
    }

    @Transactional
    public void initialize(RiskState state) {
        riskRepository.save(state);
        riskStateStore.updateCache(state);
        log.info("[RISK] State initialized/recovered. Version: {}", state.getVersion());
    }

    private void syncCacheAfterCommit(RiskState newState) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    riskStateStore.updateCache(newState);
                }
            });
        } else {
            riskStateStore.updateCache(newState);
        }
    }

    private UUID parseEventId(String eventId) {
        try {
            return UUID.fromString(eventId);
        } catch (Exception e) {
            return UUID.nameUUIDFromBytes(eventId.getBytes());
        }
    }

    public RiskState getState() {
        return riskStateStore.getState();
    }
}
