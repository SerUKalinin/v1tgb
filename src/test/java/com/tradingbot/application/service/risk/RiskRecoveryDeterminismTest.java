package com.tradingbot.application.service.risk;

import com.tradingbot.application.bootstrap.SystemStateManager;
import com.tradingbot.application.bootstrap.TradingSystemBootstrapper;
import com.tradingbot.application.risk.RiskEngine;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.execution.ExchangeOrderQueryService;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.entity.RiskReservationLogEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.infrastructure.persistence.repository.RiskReservationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
public class RiskRecoveryDeterminismTest {

    @Autowired
    private RiskEngine riskEngine;

    @Autowired
    private RiskStateRecoveryService recoveryService;

    @Autowired
    private RiskReservationLogRepository reservationLogRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private SystemStateManager stateManager;

    /**
     * Не допускаем автоматический recovery через ApplicationReadyEvent.
     * Тест запускает recovery вручную.
     */
    @MockBean
    private TradingSystemBootstrapper tradingSystemBootstrapper;

    /**
     * Не допускаем реальный scheduled reconciliation.
     */
    @MockBean
    private ReconciliationService reconciliationService;

    /**
     * Recovery должен использовать контролируемый test exchange,
     * а не реальный Binance API.
     */
    @MockBean
    private ExchangeOrderQueryService exchangeOrderQueryService;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status -> {
            reservationLogRepository.deleteAll();
            orderRepository.deleteAll();
            reservationLogRepository.flush();
            orderRepository.flush();
        });

        stateManager.updateState(
                SystemStateManager.SystemState.RISK_RECOVERING
        );

        when(
                exchangeOrderQueryService.getAvailableBalance("USDT")
        ).thenReturn(
                new BigDecimal("10000.00")
        );
    }

    @Test
    void testRecoveryDeterminismWithMixedCommitOrder()
            throws InterruptedException {

        UUID order1 = UUID.randomUUID();
        UUID order2 = UUID.randomUUID();

        UUID signal1 = UUID.randomUUID();
        UUID signal2 = UUID.randomUUID();

        UUID execution1 = UUID.randomUUID();
        UUID execution2 = UUID.randomUUID();

        createReconcilableOrder(
                order1,
                signal1,
                execution1,
                "RECOVERY-ORDER-1"
        );

        createReconcilableOrder(
                order2,
                signal2,
                execution2,
                "RECOVERY-ORDER-2"
        );

        CountDownLatch latch1 =
                new CountDownLatch(1);

        CountDownLatch latch2 =
                new CountDownLatch(1);

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {
            /*
             * Transaction 1:
             * reservation для order1.
             *
             * Вставка происходит первой, поэтому sequence_id
             * должен быть меньше sequence_id второй записи,
             * даже если commit произойдет позже.
             */
            executor.execute(() ->
                    transactionTemplate.executeWithoutResult(status -> {

                        reservationLogRepository.save(
                                RiskReservationLogEntity.builder()
                                        .id(UUID.randomUUID())
                                        .orderId(order1)
                                        .eventType("RESERVE")
                                        .amount(new BigDecimal("100"))
                                        .createdAt(Instant.now())
                                        .build()
                        );

                        reservationLogRepository.flush();

                        latch1.countDown();

                        try {
                            latch2.await();
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    })
            );

            /*
             * Transaction 2:
             * начинает работу только после insert первой reservation.
             */
            executor.execute(() -> {
                try {
                    latch1.await();

                    transactionTemplate.executeWithoutResult(status -> {

                        reservationLogRepository.save(
                                RiskReservationLogEntity.builder()
                                        .id(UUID.randomUUID())
                                        .orderId(order2)
                                        .eventType("RESERVE")
                                        .amount(new BigDecimal("200"))
                                        .createdAt(Instant.now())
                                        .build()
                        );

                        reservationLogRepository.flush();
                    });

                    latch2.countDown();

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

        } finally {
            executor.shutdown();
        }

        boolean terminated =
                executor.awaitTermination(
                        5,
                        TimeUnit.SECONDS
                );

        assertEquals(
                true,
                terminated,
                "Recovery determinism test executor did not terminate"
        );

        /*
         * После завершения обеих транзакций проверяем,
         * что reservation log действительно заполнен.
         */
        assertEquals(
                2,
                reservationLogRepository.count(),
                "Должны существовать две reservation записи"
        );

        assertNotNull(
                orderRepository.findById(order1).orElse(null),
                "Order 1 должен существовать перед recovery"
        );

        assertNotNull(
                orderRepository.findById(order2).orElse(null),
                "Order 2 должен существовать перед recovery"
        );

        /*
         * Recovery запускаем вручную ровно один раз.
         */
        recoveryService.recover();

        com.tradingbot.domain.risk.RiskState state =
                riskEngine.getState();

        /*
         * Обе reservation должны быть восстановлены,
         * потому что оба order находятся в reconcilable status.
         */
        assertEquals(
                0,
                new BigDecimal("300").compareTo(state.getReserved()),
                "Сумма восстановленных reservation должна быть 300"
        );

        assertEquals(
                2,
                state.getActiveReservations().size(),
                "Должно быть восстановлено две active reservation"
        );

        assertEquals(
                0,
                new BigDecimal("100")
                        .compareTo(state.getActiveReservations().get(order1)),
                "Reservation order1 должна быть 100"
        );

        assertEquals(
                0,
                new BigDecimal("200")
                        .compareTo(state.getActiveReservations().get(order2)),
                "Reservation order2 должна быть 200"
        );
    }

    /**
     * Создаёт минимальный persistence Order,
     * который считается reconcilable во время Risk Recovery.
     */
    private void createReconcilableOrder(
            UUID orderId,
            UUID signalId,
            UUID executionId,
            String clientOrderId
    ) {
        OrderEntity orderEntity =
                OrderEntity.builder()
                        .id(orderId)
                        .clientOrderId(clientOrderId)
                        .symbol("BTCUSDT")
                        .side(OrderSide.BUY)
                        .type(OrderType.MARKET)
                        .strategyId("risk-recovery-test")
                        .signalId(signalId)
                        .executionId(executionId)
                        .quantity(BigDecimal.ONE)
                        .price(new BigDecimal("50000"))
                        .status(OrderStatus.PENDING_EXECUTION)
                        .executionAttempts(0)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();

        orderRepository.saveAndFlush(orderEntity);
    }
}