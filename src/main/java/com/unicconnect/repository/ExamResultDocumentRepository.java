package com.unicconnect.repository;

import com.unicconnect.entity.ExamResultDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExamResultDocumentRepository extends JpaRepository<ExamResultDocument, UUID> {
    List<ExamResultDocument> findByBatch_BatchId(UUID batchId);
    List<ExamResultDocument> findByStudent_StudentId(UUID studentId);
    boolean existsByBatch_BatchIdAndStudent_StudentId(UUID batchId, UUID studentId);
}