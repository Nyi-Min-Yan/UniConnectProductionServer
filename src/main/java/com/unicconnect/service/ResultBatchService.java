package com.unicconnect.service;

import com.unicconnect.dto.request.CreateResultBatchRequest;
import com.unicconnect.dto.response.ResultBatchResponse;
import com.unicconnect.dto.response.ResultUploadSummary;
import com.unicconnect.entity.*;
import com.unicconnect.exception.BusinessRuleException;
import com.unicconnect.exception.ResourceNotFoundException;
import com.unicconnect.exception.ValidationException;
import com.unicconnect.repository.*;
import com.unicconnect.util.SecurityUtil;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ResultBatchService {

    private final ResultBatchRepository batchRepository;
    private final AcademicTermRepository termRepository;
    private final ExamTypeRepository examTypeRepository;
    private final SemesterRepository semesterRepository;
    private final StaffRepository staffRepository;
    private final StudentRepository studentRepository;
    private final ExamResultDocumentRepository documentRepository;
    private final StorageService storageService;
    private final SecurityUtil securityUtil;

    public ResultBatchService(ResultBatchRepository batchRepository,
                              AcademicTermRepository termRepository,
                              ExamTypeRepository examTypeRepository,
                              SemesterRepository semesterRepository,
                              StaffRepository staffRepository,
                              StudentRepository studentRepository,
                              ExamResultDocumentRepository documentRepository,
                              StorageService storageService,
                              SecurityUtil securityUtil) {
        this.batchRepository = batchRepository;
        this.termRepository = termRepository;
        this.examTypeRepository = examTypeRepository;
        this.semesterRepository = semesterRepository;
        this.staffRepository = staffRepository;
        this.studentRepository = studentRepository;
        this.documentRepository = documentRepository;
        this.storageService = storageService;
        this.securityUtil = securityUtil;
    }

    public List<ResultBatchResponse> getAll(UUID termId, UUID semesterId, UUID examTypeId) {
        List<ResultBatch> batches;
        if (termId != null) {
            batches = batchRepository.findByTerm_TermIdWithDetails(termId);
        } else if (semesterId != null) {
            batches = batchRepository.findBySemester_SemesterIdWithDetails(semesterId);
        } else if (examTypeId != null) {
            batches = batchRepository.findByExamType_ExamTypeIdWithDetails(examTypeId);
        } else {
            batches = batchRepository.findAllWithDetails();
        }
        return batches.stream().map(ResultBatchService::toResponse).toList();
    }

    public ResultBatchResponse getById(UUID batchId) {
        return toResponse(findBatch(batchId));
    }

    @Transactional
    public ResultBatchResponse create(CreateResultBatchRequest request) {
        AcademicTerm term = termRepository.findById(request.termId())
                .orElseThrow(() -> new ResourceNotFoundException("Academic term not found"));
        ExamType examType = examTypeRepository.findById(request.examTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Exam type not found"));
        Semester semester = semesterRepository.findById(request.semesterId())
                .orElseThrow(() -> new ResourceNotFoundException("Semester not found"));
        Staff staff = staffRepository.findById(request.uploadedByStaffId())
                .orElseThrow(() -> new ResourceNotFoundException("Staff not found"));

        ResultBatch batch = new ResultBatch();
        batch.setTerm(term);
        batch.setExamType(examType);
        batch.setSemester(semester);
        batch.setUploadedByStaff(staff);
        batch.setUploadedType(request.uploadedType());
        batch.setSourceFileName(request.sourceFileName());
        batch.setTotalFiles(request.totalFiles() != null ? request.totalFiles() : 0);
        batch.setMatchedFiles(request.matchedFiles() != null ? request.matchedFiles() : 0);
        batch.setFailedFiles(request.failedFiles() != null ? request.failedFiles() : 0);
        batch.setStatus(BatchStatus.UPLOADED);
        return toResponse(batchRepository.save(batch));
    }

    @Transactional
    public ResultBatchResponse update(UUID batchId, CreateResultBatchRequest request) {
        ResultBatch batch = findBatch(batchId);
        if (batch.getStatus() == BatchStatus.PUBLISHED) {
            throw new BusinessRuleException("Cannot modify a published batch");
        }
        batch.setTerm(termRepository.findById(request.termId())
                .orElseThrow(() -> new ResourceNotFoundException("Academic term not found")));
        batch.setExamType(examTypeRepository.findById(request.examTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Exam type not found")));
        batch.setSemester(semesterRepository.findById(request.semesterId())
                .orElseThrow(() -> new ResourceNotFoundException("Semester not found")));
        batch.setUploadedByStaff(staffRepository.findById(request.uploadedByStaffId())
                .orElseThrow(() -> new ResourceNotFoundException("Staff not found")));
        batch.setUploadedType(request.uploadedType());
        batch.setSourceFileName(request.sourceFileName());
        if (request.totalFiles() != null) batch.setTotalFiles(request.totalFiles());
        if (request.matchedFiles() != null) batch.setMatchedFiles(request.matchedFiles());
        if (request.failedFiles() != null) batch.setFailedFiles(request.failedFiles());
        return toResponse(batchRepository.save(batch));
    }

    @Transactional
    public void delete(UUID batchId) {
        ResultBatch batch = findBatch(batchId);
        if (batch.getStatus() == BatchStatus.PUBLISHED) {
            throw new BusinessRuleException("Cannot delete a published batch");
        }
        batchRepository.deleteById(batchId);
    }

    @Transactional
    public ResultBatchResponse publish(UUID batchId) {
        ResultBatch batch = findBatch(batchId);
        if (batch.getStatus() == BatchStatus.PUBLISHED) {
            throw new BusinessRuleException("Batch is already published");
        }
        batch.setStatus(BatchStatus.PUBLISHED);
        batch.setPublishedAt(Instant.now());
        return toResponse(batchRepository.save(batch));
    }

    /**
     * Multi-file upload pipeline. The student is always derived server-side from the
     * roll number embedded in the file name — never from a client-supplied student id.
     * Unmatched / failed files are reported in the summary instead of failing the
     * whole upload. Documents are upserted on the unique (batch_id, student_id) pair.
     */
    @Transactional
    public ResultUploadSummary upload(UUID termId, UUID examTypeId, UUID semesterId,
                                      UUID uploadedByStaffId, MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new ValidationException("At least one file must be uploaded");
        }
        AcademicTerm term = termRepository.findById(termId)
                .orElseThrow(() -> new ResourceNotFoundException("Academic term not found"));
        ExamType examType = examTypeRepository.findById(examTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam type not found"));
        Semester semester = semesterRepository.findById(semesterId)
                .orElseThrow(() -> new ResourceNotFoundException("Semester not found"));

        Staff staff = resolveUploader(uploadedByStaffId);
        ResultBatch batch = findOrCreateBatch(term, examType, semester, staff);

        // Existing documents of this batch (studentId -> document)
        Map<UUID, ExamResultDocument> existingDocs = documentRepository
                .findByBatch_BatchId(batch.getBatchId()).stream()
                .collect(Collectors.toMap(d -> d.getStudent().getStudentId(), d -> d, (a, b) -> a));

        List<String> skippedFileNames = new ArrayList<>();
        List<String> unmatchedFileNames = new ArrayList<>();
        List<String> failedFileNames = new ArrayList<>();
        List<String> matchedRollNames = new ArrayList<>();

        // Pass 1: normalize every file name so the student pool can be fetched in one query.
        List<FileCandidate> candidates = new ArrayList<>();
        Set<String> rollCandidates = new HashSet<>();
        for (MultipartFile file : files) {
            String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().trim();
            if (fileName.isBlank() || !fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                skippedFileNames.add(fileName);
                continue;
            }
            String roll = normalizeRollNo(fileName);
            candidates.add(new FileCandidate(file, fileName, roll));
            if (roll != null) {
                // Query both forms so students stored as "UCSTGO-<n>" can be found
                // from a bare "1234" file name and vice versa.
                rollCandidates.add(roll);
                rollCandidates.add("UCSTGO-" + roll);
            }
        }

        Map<String, Student> studentByRoll = new HashMap<>();
        if (!rollCandidates.isEmpty()) {
            for (Student s : studentRepository.findByRollNoIn(rollCandidates)) {
                studentByRoll.putIfAbsent(s.getRollNo().trim().toUpperCase(Locale.ROOT), s);
                if (s.getRollNo().startsWith("UCSTGO-")) {
                    studentByRoll.putIfAbsent(s.getRollNo().substring("UCSTGO-".length()), s);
                }
            }
        }

        int inserted = 0;
        int updated = 0;
        int matchedFiles = 0;
        int failed = 0;
        int processedPdfCount = 0;

        for (FileCandidate candidate : candidates) {
            processedPdfCount++;
            String roll = candidate.roll();
            Student student = null;
            if (roll != null) {
                student = studentByRoll.get("UCSTGO-" + roll);
                if (student == null) {
                    student = studentByRoll.get(roll);
                }
            }
            if (student == null) {
                unmatchedFileNames.add(candidate.fileName());
                continue;
            }

            String objectPath = buildObjectPath(term, examType, semester, candidate.fileName());
            try {
                storageService.store(objectPath, candidate.file().getBytes());
            } catch (Exception e) {
                failed++;
                failedFileNames.add(candidate.fileName() + " (" + e.getMessage() + ")");
                continue;
            }

            ExamResultDocument existing = existingDocs.get(student.getStudentId());
            if (existing != null) {
                existing.setPdfFileName(candidate.fileName());
                existing.setStorageObjectPath(objectPath);
                documentRepository.save(existing);
                updated++;
            } else {
                ExamResultDocument doc = new ExamResultDocument();
                doc.setBatch(batch);
                doc.setStudent(student);
                doc.setPdfFileName(candidate.fileName());
                doc.setStorageObjectPath(objectPath);
                doc.setReleaseStatus(ReleaseStatus.PENDING);
                documentRepository.save(doc);
                existingDocs.put(student.getStudentId(), doc);
                inserted++;
            }
            matchedFiles++;
            matchedRollNames.add(candidate.fileName());
        }

        // Recalculate batch stats from the unique documents actually stored.
        batch.setTotalFiles(batch.getTotalFiles() + processedPdfCount);
        batch.setMatchedFiles(existingDocs.size());
        batch.setFailedFiles(batch.getFailedFiles() + failed);
        if (batch.getStatus() == BatchStatus.UPLOADED) {
            batch.setStatus(BatchStatus.UPLOADED);
        }
        batchRepository.save(batch);

        return new ResultUploadSummary(
                batch.getBatchId(),
                toResponse(batch),
                processedPdfCount,
                matchedFiles,
                unmatchedFileNames.size(),
                inserted,
                updated,
                failed,
                skippedFileNames.size(),
                unmatchedFileNames,
                failedFileNames,
                skippedFileNames
        );
    }

    private Staff resolveUploader(UUID uploadedByStaffId) {
        if (uploadedByStaffId != null) {
            return staffRepository.findById(uploadedByStaffId)
                    .orElseThrow(() -> new ResourceNotFoundException("Staff not found"));
        }
        try {
            UUID currentUserId = securityUtil.currentUserId();
            Optional<Staff> byUser = staffRepository.findByUser_UserId(currentUserId);
            if (byUser.isPresent()) {
                return byUser.get();
            }
        } catch (Exception ignored) {
            // fall through to the fallback below
        }
        return staffRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("No staff profile found to attribute the upload"));
    }

    private ResultBatch findOrCreateBatch(AcademicTerm term, ExamType examType,
                                          Semester semester, Staff staff) {
        Optional<ResultBatch> existing = batchRepository
                .findByTerm_TermIdAndExamType_ExamTypeIdAndSemester_SemesterId(
                        term.getTermId(), examType.getExamTypeId(), semester.getSemesterId());
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            ResultBatch batch = new ResultBatch();
            batch.setTerm(term);
            batch.setExamType(examType);
            batch.setSemester(semester);
            batch.setUploadedByStaff(staff);
            batch.setUploadedType("BULK_UPLOAD");
            batch.setTotalFiles(0);
            batch.setMatchedFiles(0);
            batch.setFailedFiles(0);
            batch.setStatus(BatchStatus.UPLOADED);
            return batchRepository.save(batch);
        } catch (DataIntegrityViolationException e) {
            // Concurrent creation of the same (term, exam_type, semester) batch —
            // the unique index resolves the race deterministically.
            return batchRepository
                    .findByTerm_TermIdAndExamType_ExamTypeIdAndSemester_SemesterId(
                            term.getTermId(), examType.getExamTypeId(), semester.getSemesterId())
                    .orElseThrow(() -> new BusinessRuleException("Failed to resolve result batch"));
        }
    }

    /**
     * Accepts "UCSTGO-1234.pdf", "1234.PDF", "ucstgo-1001.pdf", "UCSTGO-2-CSA001.pdf" —
     * extension stripped, trimmed, case-insensitive, optional UCSTGO- prefix removed.
     */
    static String normalizeRollNo(String fileName) {
        String base = fileName.substring(0, fileName.lastIndexOf('.'));
        String normalized = base.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("UCSTGO-")) {
            normalized = normalized.substring("UCSTGO-".length());
        }
        return normalized.isBlank() ? null : normalized;
    }

    static String buildObjectPath(AcademicTerm term, ExamType examType, Semester semester, String fileName) {
        String semesterName = "Semester " + semester.getSemesterNo();
        return term.getAcademicYear() + "/" + examType.getExamTypeName() + "/" + semesterName + "/" + fileName;
    }

    private record FileCandidate(MultipartFile file, String fileName, String roll) {}

    public ResultBatch findBatch(UUID batchId) {
        return batchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Result batch not found"));
    }

    static ResultBatchResponse toResponse(ResultBatch batch) {
        return new ResultBatchResponse(
                batch.getBatchId(),
                batch.getTerm().getTermId(),
                batch.getTerm().getAcademicYear(),
                batch.getExamType().getExamTypeId(),
                batch.getExamType().getExamTypeName(),
                batch.getSemester().getSemesterId(),
                batch.getSemester().getSemesterNo(),
                batch.getUploadedByStaff().getStaffId(),
                batch.getUploadedByStaff().getStaffNo(),
                batch.getUploadedType(),
                batch.getSourceFileName(),
                batch.getTotalFiles(),
                batch.getMatchedFiles(),
                batch.getFailedFiles(),
                batch.getStatus(),
                batch.getUploadedAt(),
                batch.getPublishedAt());
    }
}
