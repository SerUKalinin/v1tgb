package com.tradingbot.application;

import com.tradingbot.application.service.OrderManagementService;
import com.tradingbot.application.service.OrderRecoveryService;
import com.tradingbot.common.enums.OrderSide;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.common.enums.OrderType;
import com.tradingbot.domain.execution.ExecutionEngine;
import com.tradingbot.domain.model.ExecutionResult;
import com.tradingbot.domain.model.Signal;
import com.tradingbot.domain.risk.ApprovedOrder;
import com.tradingbot.domain.risk.RiskManager;
import com.tradingbot.domain.risk.RiskState;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("test")
class OmsQaIntegrationTest {

    @Autowired
    private OrderManagementService oms;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderRecoveryService recoveryService;

    @MockBean
    private RiskManager riskManager;

    @MockBean
    private ExecutionEngine executionEngine;

    private final String symbol = "BTCUSDT";
    private final String strategyId = "strat-1";

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        reset(riskManager, executionEngine);
    }

    @Test
    void shouldExecuteOrderAsyncSuccessfully() {
        BigDecimal price = new BigDecimal("60000");
        BigDecimal qty = new BigDecimal("0.1");

        Signal signal = buildSignal(price, qty, Instant.now());

        mockRiskApproval(price, qty);
        mockExecutionSuccess(price, qty);

        oms.processSignal(signal);

        await().atMost(2, TimeUnit.SECONDS).until(() -> orderRepository.count() > 0);
        recoveryService.recoverPendingOrders();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OrderEntity> orders = orderRepository.findAll();
            assertEquals(1, orders.size());
            OrderEntity order = orders.get(0);
            assertEquals(OrderStatus.FILLED.name(), order.getStatus());
        });
    }

    @Test
    @DisplayName("TC-7: Parallel BUY signals should result in only one execution")
    void shouldExecuteOnlyOneOrderUnderRaceCondition() throws Exception {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);

        BigDecimal price = new BigDecimal("60000");
        Instant candleTime = Instant.ofEpochMilli(2000000L);

        Signal signal = buildSignal(price, BigDecimal.valueOf(0.1), candleTime);

        mockRiskApproval(price, BigDecimal.valueOf(0.1));

        when(executionEngine.execute(any())).thenAnswer(inv -> {
            Thread.sleep(100);
            return successResult(price);
        });

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    oms.processSignal(signal);
                } catch (Exception ignored) {}
            });
        }

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OrderEntity> all = orderRepository.findAll();
            assertEquals(1, all.size(), "В БД должен быть ровно один ордер");
        });

        recoveryService.recoverPendingOrders();
        
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            OrderEntity order = orderRepository.findAll().get(0);
            assertEquals(OrderStatus.FILLED.name(), order.getStatus(), "Ордер должен в итоге стать FILLED");
        });
    }

    @Test
    void shouldNotCreateDuplicateOrderAfterRestart() {
        BigDecimal price = new BigDecimal("60000");
        Instant candleTime = Instant.ofEpochMilli(1000000L);

        String clientOrderId = "c-" + strategyId + "-" + symbol + "-" + candleTime.toEpochMilli();

        orderRepository.save(OrderEntity.builder()
                .id(UUID.randomUUID().toString())
                .clientOrderId(clientOrderId)
                .symbol(symbol)
                .strategyId(strategyId)
                .status(OrderStatus.FILLED)
                .createdAt(Instant.now())
                .build());

        Signal signal = buildSignal(price, BigDecimal.valueOf(0.1), candleTime);

        oms.processSignal(signal);

        assertEquals(1, orderRepository.count());
        verify(executionEngine, never()).execute(any());
    }

    @Test
    @DisplayName("TC-15: Recovery should eventually execute pending orders")
    void shouldRecoverAndExecutePendingOrder() {
        String orderId = UUID.randomUUID().toString();
        orderRepository.save(OrderEntity.builder()
                .id(orderId)
                .clientOrderId("c-test-recovery")
                .symbol(symbol)
                .strategyId(strategyId)
                .status(OrderStatus.PENDING_EXECUTION)
                .createdAt(Instant.now().minusSeconds(60))
                .build());

        mockRiskApproval(BigDecimal.TEN, BigDecimal.ONE);
        when(executionEngine.execute(any())).thenReturn(successResult(BigDecimal.TEN));

        recoveryService.recoverPendingOrders();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            OrderEntity order = orderRepository.findById(orderId).orElseThrow();
            assertEquals(OrderStatus.FILLED, order.getStatus(), "Ордер должен быть восстановлен и исполнен");
        });
    }

    @Test
    @DisplayName("TC-5: No double execution (Lease safety)")
    void shouldNotExecuteOrderTwice() {
        String orderId = UUID.randomUUID().toString();
        orderRepository.save(OrderEntity.builder()
                .id(orderId)
                .clientOrderId("c-test-double")
                .symbol(symbol)
                .strategyId(strategyId)
                .status(OrderStatus.PENDING_EXECUTION)                .createdAt(Instant.now().minusSeconds(60))
                .build());

        mockRiskApproval(BigDecimal.TEN, BigDecimal.ONE);
        when(executionEngine.execute(any())).thenReturn(successResult(BigDecimal.TEN));

        recoveryService.recoverPendingOrders();
        recoveryService.recoverPendingOrders();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            OrderEntity order = orderRepository.findById(orderId).orElseThrow();
            assertEquals(OrderStatus.FILLED.name(), order.getStatus());
            assertEquals(1, orderRepository.count());
        });
    }

    private Signal buildSignal(BigDecimal price, BigDecimal qty, Instant time) {
        return Signal.builder()
                .symbol(symbol)
                .strategyId(strategyId)
                .side(OrderSide.BUY)
                .price(price)
                .quantity(qty)
                .clientOrderId("c-" + strategyId + "-" + symbol + "-" + time.toEpochMilli())
                .generatedAt(time)
                .build();
    }

    private void mockRiskApproval(BigDecimal price, BigDecimal qty) {
        when(riskManager.approveSignal(any(Signal.class), any(RiskState.class))).thenAnswer(inv -> {
            Signal s = inv.getArgument(0);
            return Optional.of(ApprovedOrder.builder()
                    .orderId(UUID.randomUUID().toString())
                    .clientOrderId(s.getClientOrderId())
                    .symbol(s.getSymbol())
                    .side(s.getSide())
                    .type(OrderType.MARKET)
                    .quantity(s.getQuantity())
                    .price(s.getPrice())
                    .strategyId(s.getStrategyId())
                    .approvedAt(Instant.now())
                    .riskStateVersion(1L)
                    .build());
        });
        when(riskManager.isApprovalFresh(any(), any())).thenReturn(true);
    }

    private void mockExecutionSuccess(BigDecimal price, BigDecimal qty) {
        when(executionEngine.execute(any())).thenReturn(
                ExecutionResult.success(
                        UUID.randomUUID().toString(),
                        "ex-1",
                        "tr-1",
                        symbol,
                        OrderSide.BUY,
                        qty,
                        price,
                        BigDecimal.ZERO,
                        "USDT",
                        "c"
                )
        );
    }

    private ExecutionResult successResult(BigDecimal price) {
        return ExecutionResult.success(
                UUID.randomUUID().toString(),
                "ex",
                "tr",
                symbol,
                OrderSide.BUY,
                BigDecimal.ONE,
                price,
                BigDecimal.ZERO,
                "USDT",
                "c"
        );
    }
}