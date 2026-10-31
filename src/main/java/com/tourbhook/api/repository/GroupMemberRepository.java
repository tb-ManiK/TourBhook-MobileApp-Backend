package com.tourbhook.api.repository;

import com.tourbhook.api.entity.GroupMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GroupMemberRepository extends JpaRepository<GroupMember, String> {

    interface GroupMemberCount {
        String getGroupId();

        long getTotal();
    }

    boolean existsByGroupIdAndUserId(String groupId, String userId);

    Optional<GroupMember> findByGroupIdAndUserId(String groupId, String userId);

    long countByGroupId(String groupId);

    Optional<GroupMember> findFirstByGroupIdAndUserIdNotOrderByCreatedAtAsc(String groupId, String userId);

    @Query("select m from GroupMember m join fetch m.user where m.group.id = :groupId order by m.createdAt asc")
    List<GroupMember> findAllWithUser(@Param("groupId") String groupId);

    @Query("select m from GroupMember m join fetch m.group where m.user.id = :userId order by m.createdAt desc")
    List<GroupMember> findAllWithGroupByUser(@Param("userId") String userId);

    @Query("select m.group.id as groupId, count(m) as total from GroupMember m "
            + "where m.group.id in :groupIds group by m.group.id")
    List<GroupMemberCount> countByGroupIds(@Param("groupIds") Collection<String> groupIds);

    @Query("select m from GroupMember m where m.user.id = :userId and m.group.id in :groupIds")
    List<GroupMember> findByUserAndGroupIds(@Param("userId") String userId,
                                            @Param("groupIds") Collection<String> groupIds);
}