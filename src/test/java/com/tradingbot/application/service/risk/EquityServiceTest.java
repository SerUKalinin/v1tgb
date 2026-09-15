package com.tradingbot.application.service.risk;

import com.tradingbot.application.service.execution.PositionService;
import com.tradingbot.domain.model.Position;
import com.tradingbot.domain.risk.RiskState;
import com.tradingbot.domain.risk.RiskStatePort;
import com.tradingbot.infrastructure.persistence.entity.EquitySnapshotEntity;
import com.tradingbot.infrastructure.persistence.repository.EquitySnapshotRepository;
import com.tradingbot.infrastructure.persistence.repository.TradeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EquityServiceTest {

    @Mock
    private EquitySnapshotRepository equityRepository;

    @Mock
    private PositionService positionService;

    @Mock
    private TradeRepository tradeRepository;

    @Mock
    private RiskStatePort riskStatePort;

    @InjectMocks
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

        ArgumentCaptor<EquitySnapshotEntity> snapshotCaptor =
                ArgumentCaptor.forClass(
                        EquitySnapshotEntity.class
                );

        verify(equityRepository)
                .save(snapshotCaptor.capture());

        EquitySnapshotEntity snapshot =
                snapshotCaptor.getValue();

        assertThat(snapshot.getBalance())
                .isEqualByComparingTo(
                        canonicalBalance
                );

        assertThat(snapshot.getUnrealizedPnl())
                .isEqualByComparingTo(
                        BigDecimal.ZERO
                );

        assertThat(snapshot.getEquity())
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

        ArgumentCaptor<EquitySnapshotEntity> snapshotCaptor =
                ArgumentCaptor.forClass(
                        EquitySnapshotEntity.class
                );

        verify(equityRepository)
                .save(snapshotCaptor.capture());

        EquitySnapshotEntity snapshot =
                snapshotCaptor.getValue();

        assertThat(snapshot.getBalance())
                .isEqualByComparingTo(
                        new BigDecimal("17000.00")
                );

        assertThat(snapshot.getUnrealizedPnl())
                .isEqualByComparingTo(
                        new BigDecimal("0.400")
                );

        assertThat(snapshot.getEquity())
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
}