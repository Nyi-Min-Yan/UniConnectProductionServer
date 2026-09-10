package com.unicconnect.service;

import com.unicconnect.dto.response.ResultDocumentResponse;
import com.unicconnect.entity.*;
import com.unicconnect.exception.BusinessRuleException;
import com.unicconnect.exception.ResourceNotFoundException;
import com.unicconnect.repository.ExamResultDocumentRepository;
import com.unicconnect.repository.ResultBatchRepository;
import com.unicconnect.repository.StudentRepository;
import com.unicconnect.util.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ResultDocumentService {

    private final ExamResultDocumentRepository documentRepository;
    private final ResultBatchRepository batchRepository;
    private final StudentRepository studentRepository;
    private final SecurityUtil securityUtil;

    public ResultDocumentService(ExamResultDocumentRepository documentRepository,
                                 ResultBatchRepository batchRepository,
                                 StudentRepository studentRepository,
                                 SecurityUtil securityUtil) {
        this.documentRepository = documentRepository;
        this.batchRepository = batchRepository;
        this.studentRepository = studentRepository;
        this.securityUtil = securityUtil;
    }

    public List<ResultDocumentResponse> getAll(UUID batchId) {
        if (batchId == null) {
            throw new com.unicconnect.exception.ValidationException("batchId query parameter is required");
        }
        List<ExamResultDocument> docs = documentRepository.findByBatch_BatchId(batchId);
        return docs.stream().map(ResultDocumentService::toResponse).toList();
    }

    public ResultDocumentResponse getById(UUID documentId) {
        return toResponse(findDocument(documentId));
    }

    public ResultDocumentResponse getByStudentAndBatch(UUID batchId, UUID studentId) {
        ExamResultDocument doc = documentRepository.findByBatch_BatchId(batchId).stream()
                .filter(d -> d.getStudent().getStudentId().equals(studentId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Result document not found for this student and batch"));
        return toResponse(doc);
    }

    public List<ResultDocumentResponse> getByStudent(UUID studentId) {
        verifyStudentAccess(studentId);
        return documentRepository.findByStudent_StudentId(studentId).stream()
                .map(ResultDocumentService::toResponse).toList();
    }

    @Transactional
    public ResultDocumentResponse release(UUID documentId) {
        ExamResultDocument doc = findDocument(documentId);
        if (doc.getReleaseStatus() == ReleaseStatus.RELEASED) {
            throw new BusinessRuleException("Document is already released");
        }
        doc.setReleaseStatus(ReleaseStatus.RELEASED);
        doc.setBlockedReason(null);
        return toResponse(documentRepository.save(doc));
    }

    @Transactional
    public ResultDocumentResponse block(UUID documentId, String reason) {
        ExamResultDocument doc = findDocument(documentId);
        doc.setReleaseStatus(ReleaseStatus.BLOCKED);
        doc.setBlockedReason(reason);
        return toResponse(documentRepository.save(doc));
    }

    @Transactional
    public ResultDocumentResponse recordView(UUID documentId) {
        ExamResultDocument doc = findDocument(documentId);
        doc.setViewedAt(Instant.now());
        return toResponse(documentRepository.save(doc));
    }

    @Transactional
    public ResultDocumentResponse recordDownload(UUID documentId) {
        ExamResultDocument doc = findDocument(documentId);
        doc.setDownloadedAt(Instant.now());
        return toResponse(documentRepository.save(doc));
    }

    @Transactional
    public void delete(UUID documentId) {
        ExamResultDocument doc = findDocument(documentId);
        if (doc.getReleaseStatus() == ReleaseStatus.RELEASED) {
            throw new BusinessRuleException("Cannot delete a released document");
        }
        documentRepository.deleteById(documentId);
    }

    private void verifyStudentAccess(UUID studentId) {
        if (securityUtil.isAdmin() || securityUtil.isStaff()) {
            return;
        }
        UUID currentUserId = securityUtil.currentUserId();
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new ResourceNotFoundException("Student not found"));
        if (!student.getUser().getUserId().equals(currentUserId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "You are not authorized to view this student's results");
        }
    }

    public ExamResultDocument findDocument(UUID documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Result document not found"));
    }

    static ResultDocumentResponse toResponse(ExamResultDocument doc) {
        return new ResultDocumentResponse(
                doc.getResultDocumentId(),
                doc.getBatch().getBatchId(),
                doc.getBatch().getExamType().getExamTypeName(),
                doc.getStudent().getStudentId(),
                doc.getStudent().getRollNo(),
                doc.getStudent().getStudentName(),
                doc.getPdfFileName(),
                doc.getStorageObjectPath(),
                doc.getReleaseStatus(),
                doc.getBlockedReason(),
                doc.getViewedAt(),
                doc.getDownloadedAt(),
                doc.getCreatedAt());
    }
}
