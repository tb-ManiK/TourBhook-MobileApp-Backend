package com.tourbhook.api.entity;

import com.tourbhook.api.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "ugc_links",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ugc_links_user_destination_content",
                columnNames = {"user_id", "destination_key", "platform", "external_id"}),
        indexes = {
                @Index(name = "idx_ugc_links_destination", columnList = "destination_key,status,created_at"),
                @Index(name = "idx_ugc_links_user_created", columnList = "user_id,created_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UgcLink extends BaseEntity {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 120)
    private String destination;

    @Column(name = "destination_key", nullable = false, length = 120)
    private String destinationKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "place_id")
    private Place place;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UgcLinkPlatform platform;

    @Column(name = "external_id", nullable = false, length = 40)
    private String externalId;

    @Column(nullable = false, length = 2000)
    private String url;

    @Column(length = 300)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UgcLinkStatus status;

    @Column(nullable = false)
    private LocalDateTime moderatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = UgcLinkStatus.ACTIVE;
        }
    }
}