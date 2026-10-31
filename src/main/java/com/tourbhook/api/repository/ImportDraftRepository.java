package com.tourbhook.api.repository;

import com.tourbhook.api.entity.ImportDraft;
import com.tourbhook.api.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ImportDraftRepository extends JpaRepository<ImportDraft, String> {

    Optional<ImportDraft> findByIdAndUser(String id, User user);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from ImportDraft d where d.id = :id and d.user = :user")
    Optional<ImportDraft> findByIdAndUserForUpdate(@Param("id") String id, @Param("user") User user);
}