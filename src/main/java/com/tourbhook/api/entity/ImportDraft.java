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
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(
        name = "import_drafts",
        indexes = @Index(name = "idx_import_drafts_user_status", columnList = "user_id,status")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImportDraft extends BaseEntity {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ImportSourceType sourceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ImportDraftStatus status;

    @Lob
    @Column(name = "payload_json", nullable = false)
    private String payloadJson;

    /** Set once the draft has been committed; lets the client jump straight to the trip. */
    @Column(name = "committed_trip_id", length = 36)
    private String committedTripId;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = ImportDraftStatus.DRAFT;
        }
    }
}