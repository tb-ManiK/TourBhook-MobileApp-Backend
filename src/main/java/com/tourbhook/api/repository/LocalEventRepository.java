package com.tourbhook.api.repository;

import com.tourbhook.api.entity.LocalEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface LocalEventRepository extends JpaRepository<LocalEvent, String> {

    List<LocalEvent> findBySourceAndExternalIdIn(String source, Collection<String> externalIds);

    @Query("select e from LocalEvent e where e.active = true and e.cityKey = :cityKey "
            + "and e.startDate <= :to and e.endDate >= :from "
            + "order by e.startDate asc, e.startTime asc, e.id asc")
    List<LocalEvent> findForCity(@Param("cityKey") String cityKey,
                                 @Param("from") LocalDate from,
                                 @Param("to") LocalDate to,
                                 Pageable pageable);

    @Query("select e from LocalEvent e where e.active = true "
            + "and e.latitude between :minLat and :maxLat and e.longitude between :minLng and :maxLng "
            + "and e.startDate <= :to and e.endDate >= :from "
            + "order by e.startDate asc, e.startTime asc, e.id asc")
    List<LocalEvent> findInBoundingBox(@Param("minLat") double minLat,
                                       @Param("maxLat") double maxLat,
                                       @Param("minLng") double minLng,
                                       @Param("maxLng") double maxLng,
                                       @Param("from") LocalDate from,
                                       @Param("to") LocalDate to,
                                       Pageable pageable);

    @Modifying
    @Query("update LocalEvent e set e.active = false "
            + "where e.source = :source and e.cityKey = :cityKey and e.active = true "
            + "and e.lastSyncedAt < :syncStartedAt and e.endDate >= :today")
    int deactivateUnseen(@Param("source") String source,
                         @Param("cityKey") String cityKey,
                         @Param("syncStartedAt") LocalDateTime syncStartedAt,
                         @Param("today") LocalDate today);

    @Modifying
    @Query("delete from LocalEvent e where e.endDate < :cutoff")
    int deleteEndedBefore(@Param("cutoff") LocalDate cutoff);

    @Query("select max(e.lastSyncedAt) from LocalEvent e where e.active = true")
    LocalDateTime findLastSyncedAt();
}