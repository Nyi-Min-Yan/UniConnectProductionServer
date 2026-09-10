package com.unicconnect.repository;

import com.unicconnect.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StudentRepository extends JpaRepository<Student, UUID> {
    Optional<Student> findByRollNo(String rollNo);
    boolean existsByRollNo(String rollNo);
    Optional<Student> findByUser_UserId(UUID userId);
    List<Student> findByMajor_MajorId(UUID majorId);
    List<Student> findBySemester_SemesterId(UUID semesterId);
    List<Student> findBySection_SectionId(UUID sectionId);

/** COHORT-AWARE roster: course semester + covered section(s). A section name
 *  is shared by several semester cohorts, so Roll Call must never query by
 *  section alone. */
List<Student> findBySection_SectionIdAndSemester_SemesterId(UUID sectionId, UUID semesterId);
List<Student> findBySection_SectionIdInAndSemester_SemesterId(java.util.List<UUID> sectionIds, UUID semesterId);
    List<Student> findByTerm_TermId(UUID termId);
    boolean existsByUser_UserId(UUID userId);
    List<Student> findByRollNoIn(java.util.Collection<String> rollNos);

    String FETCH_JOINS = " join fetch s.user u join fetch s.major m"
            + " left join fetch s.term t left join fetch s.semester se left join fetch s.section sc";

    @Query("select s from Student s" + FETCH_JOINS)
    List<Student> findAllWithDetails();

    @Query("select s from Student s" + FETCH_JOINS + " where s.major.majorId = :majorId")
    List<Student> findByMajor_MajorIdWithDetails(@Param("majorId") UUID majorId);

    @Query("select s from Student s" + FETCH_JOINS + " where s.semester.semesterId = :semesterId")
    List<Student> findBySemester_SemesterIdWithDetails(@Param("semesterId") UUID semesterId);

    @Query("select s from Student s" + FETCH_JOINS + " where s.section.sectionId = :sectionId")
    List<Student> findBySection_SectionIdWithDetails(@Param("sectionId") UUID sectionId);

    @Query("select s from Student s" + FETCH_JOINS + " where s.term.termId = :termId")
    List<Student> findByTerm_TermIdWithDetails(@Param("termId") UUID termId);
}