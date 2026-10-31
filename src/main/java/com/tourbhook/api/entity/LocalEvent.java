package com.tourbhook.api.entity;

import com.tourbhook.api.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(
        name = "local_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_local_events_source_external", columnNames = {"source", "external_id"}),
        indexes = {
                @Index(name = "idx_local_events_city_dates", columnList = "city_key,start_date,end_date"),
                @Index(name = "idx_local_events_geo", columnList = "latitude,longitude"),
                @Index(name = "idx_local_events_active_end", columnList = "active,end_date")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LocalEvent extends BaseEntity {

    @Id
    private String id;

    @Column(nullable = false, length = 40)
    private String source;

    @Column(name = "external_id", nullable = false, length = 300)
    private String externalId;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(length = 1000)
    private String description;

    @Column(length = 60)
    private String category;

    @Column(length = 200)
    private String venueName;

    @Column(length = 500)
    private String address;

    @Column(nullable = false, length = 120)
    private String city;

    @Column(name = "city_key", nullable = false, length = 120)
    private String cityKey;

    private Double latitude;

    private Double longitude;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    private LocalTime startTime;

    @Column(length = 2000)
    private String url;

    @Column(length = 2000)
    private String imageUrl;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private LocalDateTime lastSyncedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}