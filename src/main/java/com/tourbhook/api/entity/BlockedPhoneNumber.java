package com.tourbhook.api.entity;

import com.tourbhook.api.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "blocked_phone_numbers",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_blocked_phone_e164", columnNames = {"phone_e164"})
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BlockedPhoneNumber extends BaseEntity {

    @Id
    private String id;

    @Column(name = "phone_e164", nullable = false, length = 20)
    private String phoneE164;

    @Column(length = 255)
    private String reason;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID().toString();
    }
}