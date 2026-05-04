package com.tradingbot.infrastructure.persistence.entity;

import com.tradingbot.infrastructure.persistence.converter.ActiveReservationsConverter;
import com.tradingbot.infrastructure.persistence.converter.ProcessedEventIdsConverter;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "risk_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@org.hibernate.annotations.DynamicUpdate
public class RiskStateEntity implements Persistable<String> {
    public static final String SINGLETON_ID = "GLOBAL";

    @Id
    @Column(name = "id", length = 20)
    @Builder.Default
    private String id = SINGLETON_ID;

    @Override
    public String getId() {
        return id;
    }

    @Override
    @Transient
    public boolean isNew() {
        return version == null;
    }
    @Column(name = "available_balance", nullable = false, precision = 38, scale = 18)
    private BigDecimal availableBalance;

    @Column(name = "reserved_margin", nullable = false, precision = 38, scale = 18)
    private BigDecimal reservedMargin;

    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal totalEquity;    @Column(nullable = false)
    private boolean halted;

    @Convert(converter = ActiveReservationsConverter.class)
    @Column(name = "active_reservations", columnDefinition = "TEXT")
    private Map<UUID, BigDecimal> activeReservations;

    @Convert(converter = ProcessedEventIdsConverter.class)
    @Column(name = "processed_event_ids", columnDefinition = "TEXT")
    private Set<String> processedEventIds;

    @Version
    private Long version;
    @Column(name = "updated_at")
    private Instant updatedAt;
}
