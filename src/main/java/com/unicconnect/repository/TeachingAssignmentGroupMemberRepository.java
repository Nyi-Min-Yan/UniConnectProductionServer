package com.unicconnect.repository;

import com.unicconnect.entity.TeachingAssignmentGroupMember;
import com.unicconnect.entity.TeachingAssignmentGroupMemberId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TeachingAssignmentGroupMemberRepository
        extends JpaRepository<TeachingAssignmentGroupMember, TeachingAssignmentGroupMemberId> {

    boolean existsByAssignment_AssignmentId(UUID assignmentId);

    List<TeachingAssignmentGroupMember> findAllByGroup_GroupId(UUID groupId);

    List<TeachingAssignmentGroupMember> findAllByAssignment_AssignmentId(UUID assignmentId);

    @Query("select m from TeachingAssignmentGroupMember m " +
            "join fetch m.group g join fetch g.course gc left join fetch gc.semester " +
            "join fetch g.term " +
            "join fetch m.assignment a join fetch a.course ac left join fetch ac.semester " +
            "join fetch a.section join fetch a.staff join fetch a.term " +
            "where g.term.termId = :termId")
    List<TeachingAssignmentGroupMember> findWithDetailsByTermId(@Param("termId") UUID termId);

    @Query("select m from TeachingAssignmentGroupMember m " +
            "join fetch m.group g join fetch g.course gc left join fetch gc.semester " +
            "join fetch g.term " +
            "join fetch m.assignment a join fetch a.course ac left join fetch ac.semester " +
            "join fetch a.section join fetch a.staff join fetch a.term " +
            "where g.groupId = :groupId")
    List<TeachingAssignmentGroupMember> findWithDetailsByGroupId(@Param("groupId") UUID groupId);
}
