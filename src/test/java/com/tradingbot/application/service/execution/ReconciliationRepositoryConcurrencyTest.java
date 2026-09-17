package com.tradingbot.application.service.execution;

import com.tradingbot.application.bootstrap.TradingSystemBootstrapper;
import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.domain.model.Order;
import com.tradingbot.infrastructure.persistence.adapter.OrderRepositoryAdapter;
import com.tradingbot.infrastructure.persistence.mapper.OrderMapper;
import com.tradingbot.infrastructure.persistence.repository.OrderRepository;
import com.tradingbot.testutil.TestOrderFactory;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(
        properties = {
                "spring.task.scheduling.enabled=false"
        }
)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ReconciliationRepositoryConcurrencyTest {

    @MockBean
    private TradingSystemBootstrapper tradingSystemBootstrapper;

    @Autowired
    private OrderRepositoryAdapter orderRepositoryAdapter;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderMapper orderMapper;

    private TestOrderFactory orderFactory;

    @BeforeEach
    void setUp() {
        orderFactory = new TestOrderFactory(
                orderRepository,
                orderMapper
        );
    }

    @Test
    void onlyOneConcurrentReconciliationClaimMaySucceed()
            throws Exception {

        /*
         * Создаём реальный OrderEntity в H2.
         *
         * SENT_TO_EXCHANGE — допустимое состояние для reconciliation.
         */
        Order persistedOrder =
                orderFactory.create(
                        OrderStatus.SENT_TO_EXCHANGE
                );

        int workers = 2;

        ExecutorService executor =
                Executors.newFixedThreadPool(workers);

        CountDownLatch start =
                new CountDownLatch(1);

        AtomicInteger successfulClaims =
                new AtomicInteger(0);

        List<Future<Optional<Order>>> futures =
                new ArrayList<>();

        try {

            for (int i = 0; i < workers; i++) {

                futures.add(
                        executor.submit(() -> {

                            /*
                             * Оба worker одновременно входят
                             * в реальный OrderRepositoryAdapter.
                             */
                            start.await();

                            Optional<Order> claimed =
                                    orderRepositoryAdapter
                                            .claimForReconciliation(
                                                    persistedOrder.getId()
                                            );

                            if (claimed.isPresent()) {
                                successfulClaims.incrementAndGet();
                            }

                            return claimed;
                        })
                );
            }

            /*
             * Одновременный старт.
             */
            start.countDown();

            /*
             * Ждём завершения обоих worker.
             */
            for (Future<Optional<Order>> future : futures) {
                future.get(
                        10,
                        TimeUnit.SECONDS
                );
            }

        } finally {

            executor.shutdownNow();

            Assertions.assertThat(
                    executor.awaitTermination(
                            10,
                            TimeUnit.SECONDS
                    )
            ).isTrue();
        }

        /*
         * КРИТИЧЕСКАЯ ПРОВЕРКА.
         *
         * На один Order должен существовать
         * только один успешный reconciliation claim.
         */
        Assertions.assertThat(
                        successfulClaims.get()
                )
                .as("Only one reconciliation worker may own the order")
                .isEqualTo(1);

        /*
         * Проверяем, что Order всё ещё существует
         * в реальной БД.
         */
        Order finalOrder =
                orderRepositoryAdapter
                        .findById(
                                persistedOrder.getId()
                        )
                        .orElseThrow();

        Assertions.assertThat(
                        finalOrder.getId()
                )
                .isEqualTo(
                        persistedOrder.getId()
                );

        Assertions.assertThat(
                        finalOrder.getStatus()
                )
                .isEqualTo(
                        OrderStatus.SENT_TO_EXCHANGE
                );
    }
}