package com.unicconnect.repository;

import com.unicconnect.entity.TeachingAssignmentGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TeachingAssignmentGroupRepository extends JpaRepository<TeachingAssignmentGroup, UUID> {

    List<TeachingAssignmentGroup> findByTerm_TermId(UUID termId);

    boolean existsByTerm_TermIdAndCourse_CourseId(UUID termId, UUID courseId);

    @Query("select g from TeachingAssignmentGroup g " +
            "join fetch g.course c left join fetch c.semester " +
            "join fetch g.term " +
            "where g.term.termId = :termId " +
            "order by c.courseCode")
    List<TeachingAssignmentGroup> findWithCourseByTermId(@Param("termId") UUID termId);
}
