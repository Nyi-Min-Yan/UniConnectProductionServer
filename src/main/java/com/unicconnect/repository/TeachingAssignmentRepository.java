package com.unicconnect.repository;

import com.unicconnect.entity.TeachingAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TeachingAssignmentRepository extends JpaRepository<TeachingAssignment, UUID> {
    List<TeachingAssignment> findByTerm_TermId(UUID termId);
    List<TeachingAssignment> findByStaff_StaffId(UUID staffId);
    List<TeachingAssignment> findByCourse_CourseId(UUID courseId);
    List<TeachingAssignment> findBySection_SectionId(UUID sectionId);
    Optional<TeachingAssignment> findByTerm_TermIdAndCourse_CourseIdAndSection_SectionId(UUID termId, UUID courseId, UUID sectionId);
    boolean existsByTerm_TermIdAndCourse_CourseIdAndSection_SectionId(UUID termId, UUID courseId, UUID sectionId);
    boolean existsByTerm_TermIdAndCourse_CourseId(UUID termId, UUID courseId);

    @Query("select ta from TeachingAssignment ta join fetch ta.course c left join fetch c.semester " +
           "join fetch ta.section join fetch ta.term " +
           "join fetch ta.staff st left join fetch st.user left join fetch st.unit " +
           "where ta.term.termId = :termId")
    List<TeachingAssignment> findWithDetailsByTermId(@Param("termId") UUID termId);
}