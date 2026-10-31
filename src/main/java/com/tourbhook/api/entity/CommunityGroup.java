package com.tourbhook.api.entity;

import com.tourbhook.api.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
        name = "community_groups",
        indexes = @Index(name = "idx_community_groups_city", columnList = "city")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommunityGroup extends BaseEntity {

    @Id
    private String id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(nullable = false, length = 120)
    private String city;

    private LocalDate startDate;

    private LocalDate endDate;

    @Column(nullable = false)
    private int maxMembers;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}