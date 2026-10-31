package com.tourbhook.api.repository;

import com.tourbhook.api.entity.UgcLink;
import com.tourbhook.api.entity.UgcLinkPlatform;
import com.tourbhook.api.entity.UgcLinkStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UgcLinkRepository extends JpaRepository<UgcLink, String> {

    Optional<UgcLink> findByUserIdAndDestinationKeyAndPlatformAndExternalId(
            String userId, String destinationKey, UgcLinkPlatform platform, String externalId);

    Optional<UgcLink> findByIdAndUserId(String id, String userId);

    long countByUserIdAndCreatedAtAfter(String userId, LocalDateTime since);

    @Query("select l from UgcLink l join fetch l.user where l.destinationKey = :destinationKey "
            + "and l.status = :status order by l.createdAt desc, l.id desc")
    List<UgcLink> findForDestination(@Param("destinationKey") String destinationKey,
                                     @Param("status") UgcLinkStatus status,
                                     Pageable pageable);

    @Query("select l from UgcLink l join fetch l.user where l.user.id = :userId "
            + "and l.status = :status order by l.createdAt desc, l.id desc")
    List<UgcLink> findForUser(@Param("userId") String userId,
                              @Param("status") UgcLinkStatus status,
                              Pageable pageable);
}