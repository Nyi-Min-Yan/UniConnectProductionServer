package com.unicconnect.repository;

import com.unicconnect.entity.ResultBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResultBatchRepository extends JpaRepository<ResultBatch, UUID> {

    String FETCH_JOINS = " join fetch b.term join fetch b.examType"
            + " join fetch b.semester join fetch b.uploadedByStaff";

    @Query("select b from ResultBatch b" + FETCH_JOINS + " where b.term.termId = :termId")
    List<ResultBatch> findByTerm_TermIdWithDetails(UUID termId);

    @Query("select b from ResultBatch b" + FETCH_JOINS + " where b.semester.semesterId = :semesterId")
    List<ResultBatch> findBySemester_SemesterIdWithDetails(UUID semesterId);

    @Query("select b from ResultBatch b" + FETCH_JOINS + " where b.examType.examTypeId = :examTypeId")
    List<ResultBatch> findByExamType_ExamTypeIdWithDetails(UUID examTypeId);

    @Query("select b from ResultBatch b" + FETCH_JOINS)
    List<ResultBatch> findAllWithDetails();

    List<ResultBatch> findByTerm_TermId(UUID termId);
    List<ResultBatch> findBySemester_SemesterId(UUID semesterId);
    List<ResultBatch> findByExamType_ExamTypeId(UUID examTypeId);
    Optional<ResultBatch> findByTerm_TermIdAndExamType_ExamTypeIdAndSemester_SemesterId(
            UUID termId, UUID examTypeId, UUID semesterId);
}