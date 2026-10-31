package com.tourbhook.api.repository;

import com.tourbhook.api.entity.CommunityGroup;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CommunityGroupRepository extends JpaRepository<CommunityGroup, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from CommunityGroup g where g.id = :id")
    Optional<CommunityGroup> findByIdForUpdate(@Param("id") String id);

    @Query("select g from CommunityGroup g order by g.createdAt desc, g.id desc")
    List<CommunityGroup> findLatest(Pageable pageable);

    @Query("select g from CommunityGroup g where lower(g.city) = lower(:city) order by g.createdAt desc, g.id desc")
    List<CommunityGroup> findLatestByCity(@Param("city") String city, Pageable pageable);
}