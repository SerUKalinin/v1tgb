package com.tradingbot.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "risk_reservation_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RiskReservationLogEntity {

    @Id
    private UUID id;

    // IMPORTANT:
    // sequence_id represents logical event ordering (NOT commit order)
    // must be used only for deterministic replay, not real-time ordering
    @SequenceGenerator(
            name = "risk_reservation_seq_gen",
            sequenceName = "risk_reservation_seq",
            allocationSize = 1
    )    @GeneratedValue(
            strategy = GenerationType.SEQUENCE,
            generator = "risk_reservation_seq_gen"
    )
    @Column(name = "sequence_id", nullable = false, unique = true, updatable = false)
    private Long sequenceId;    @Column(name = "order_id", nullable = false)    private UUID orderId;

    @Column(name = "client_order_id")
    private String clientOrderId;

    @Column(name = "event_type", nullable = false, length = 20)
    private String eventType;

    @Column(name = "amount", nullable = false, precision = 38, scale = 18)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
