package com.tourbhook.api.repository;

import com.tourbhook.api.entity.GroupMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface GroupMessageRepository extends JpaRepository<GroupMessage, String> {

    @Query("select m from GroupMessage m join fetch m.sender where m.group.id = :groupId "
            + "order by m.createdAt desc, m.id desc")
    List<GroupMessage> findLatest(@Param("groupId") String groupId, Pageable pageable);

    @Query("select m from GroupMessage m join fetch m.sender where m.group.id = :groupId "
            + "and (m.createdAt < :createdAt or (m.createdAt = :createdAt and m.id < :id)) "
            + "order by m.createdAt desc, m.id desc")
    List<GroupMessage> findOlderThan(@Param("groupId") String groupId,
                                     @Param("createdAt") LocalDateTime createdAt,
                                     @Param("id") String id,
                                     Pageable pageable);

    @Modifying
    @Query("delete from GroupMessage m where m.group.id = :groupId")
    void deleteByGroupId(@Param("groupId") String groupId);
}