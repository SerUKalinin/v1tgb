package com.tradingbot.application.service.risk;

import com.tradingbot.application.service.execution.PositionService;
import com.tradingbot.domain.model.EquitySnapshot;
import com.tradingbot.domain.model.EquitySnapshotPort;
import com.tradingbot.domain.model.Position;
import com.tradingbot.domain.risk.RiskState;
import com.tradingbot.domain.risk.RiskStatePort;
import com.tradingbot.infrastructure.persistence.repository.TradeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EquityServiceTest {

    @Mock
    private PositionService positionService;

    @Mock
    private TradeRepository tradeRepository;

    @Mock
    private RiskStatePort riskStatePort;

    @Mock
    private EquitySnapshotPort equitySnapshotPort;

    @org.mockito.InjectMocks
    private EquityService equityService;

    @Test
    void shouldUseCanonicalRiskStateBalanceForSnapshot() {

        BigDecimal canonicalBalance =
                new BigDecimal("17402.38684110");

        RiskState riskState =
                RiskState.builder()
                        .balance(canonicalBalance)
                        .totalEquity(canonicalBalance)
                        .halted(false)
                        .build();

        when(riskStatePort.get())
                .thenReturn(riskState);

        when(positionService.getPosition(
                "BTCUSDT",
                "test-strategy"
        )).thenReturn(null);

        equityService.createSnapshot(
                "test-strategy",
                "BTCUSDT",
                new BigDecimal("77416.01")
        );

        ArgumentCaptor<EquitySnapshot> snapshotCaptor =
                ArgumentCaptor.forClass(
                        EquitySnapshot.class
                );

        verify(equitySnapshotPort)
                .save(snapshotCaptor.capture());

        EquitySnapshot snapshot =
                snapshotCaptor.getValue();

        assertThat(readProperty(snapshot, "balance"))
                .isEqualByComparingTo(
                        canonicalBalance
                );

        assertThat(readProperty(snapshot, "unrealizedPnl"))
                .isEqualByComparingTo(
                        BigDecimal.ZERO
                );

        assertThat(readProperty(snapshot, "equity"))
                .isEqualByComparingTo(
                        canonicalBalance
                );

        verify(riskStatePort)
                .get();

        verify(positionService)
                .getPosition(
                        "BTCUSDT",
                        "test-strategy"
                );
    }

    @Test
    void shouldCalculateEquityUsingCanonicalBalanceAndUnrealizedPnl() {

        BigDecimal canonicalBalance =
                new BigDecimal("17000.00");

        RiskState riskState =
                RiskState.builder()
                        .balance(canonicalBalance)
                        .totalEquity(canonicalBalance)
                        .halted(false)
                        .build();

        when(riskStatePort.get())
                .thenReturn(riskState);

        Position position =
                mock(Position.class);

        when(position.getNetQuantity())
                .thenReturn(
                        new BigDecimal("0.001")
                );

        when(position.getAvgEntryPrice())
                .thenReturn(
                        new BigDecimal("77000")
                );

        when(positionService.getPosition(
                "BTCUSDT",
                "test-strategy"
        )).thenReturn(position);

        equityService.createSnapshot(
                "test-strategy",
                "BTCUSDT",
                new BigDecimal("77400")
        );

        ArgumentCaptor<EquitySnapshot> snapshotCaptor =
                ArgumentCaptor.forClass(
                        EquitySnapshot.class
                );

        verify(equitySnapshotPort)
                .save(snapshotCaptor.capture());

        EquitySnapshot snapshot =
                snapshotCaptor.getValue();

        assertThat(readProperty(snapshot, "balance"))
                .isEqualByComparingTo(
                        new BigDecimal("17000.00")
                );

        assertThat(readProperty(snapshot, "unrealizedPnl"))
                .isEqualByComparingTo(
                        new BigDecimal("0.400")
                );

        assertThat(readProperty(snapshot, "equity"))
                .isEqualByComparingTo(
                        new BigDecimal("17000.400")
                );

        verify(riskStatePort)
                .get();

        verify(positionService)
                .getPosition(
                        "BTCUSDT",
                        "test-strategy"
                );
    }

    private static BigDecimal readProperty(
            EquitySnapshot snapshot,
            String property
    ) {
        try {
            String getterName =
                    "get"
                            + Character.toUpperCase(
                            property.charAt(0)
                    )
                            + property.substring(1);

            try {
                Object value =
                        snapshot
                                .getClass()
                                .getMethod(getterName)
                                .invoke(snapshot);

                return (BigDecimal) value;

            } catch (NoSuchMethodException ignored) {

                Object value =
                        snapshot
                                .getClass()
                                .getMethod(property)
                                .invoke(snapshot);

                return (BigDecimal) value;
            }

        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                    "Cannot read EquitySnapshot property '"
                            + property
                            + "' from "
                            + snapshot.getClass().getName(),
                    e
            );
        }
    }
}