package com.unicconnect.service;

import com.unicconnect.dto.request.AcademicTermRequest;
import com.unicconnect.dto.response.*;
import com.unicconnect.entity.AcademicTerm;
import com.unicconnect.entity.TermStatus;
import com.unicconnect.exception.ResourceNotFoundException;
import com.unicconnect.exception.ValidationException;
import com.unicconnect.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AcademicTermService {

    private final AcademicTermRepository termRepository;
    private final StudentRepository studentRepository;
    private final TeachingAssignmentRepository teachingAssignmentRepository;
    private final ResultBatchRepository resultBatchRepository;
    private final ClassScheduleRepository classScheduleRepository;

    public AcademicTermService(AcademicTermRepository termRepository,
                               StudentRepository studentRepository,
                               TeachingAssignmentRepository teachingAssignmentRepository,
                               ResultBatchRepository resultBatchRepository,
                               ClassScheduleRepository classScheduleRepository) {
        this.termRepository = termRepository;
        this.studentRepository = studentRepository;
        this.teachingAssignmentRepository = teachingAssignmentRepository;
        this.resultBatchRepository = resultBatchRepository;
        this.classScheduleRepository = classScheduleRepository;
    }

    public List<AcademicTermResponse> getAll() {
        return termRepository.findAll().stream().map(AcademicTermService::toResponse).toList();
    }

    public AcademicTermResponse getById(UUID termId) {
        return toResponse(findTerm(termId));
    }

    @Transactional
    public AcademicTermResponse create(AcademicTermRequest request) {
        if (request.endDate() != null && request.startDate() != null
                && request.endDate().isBefore(request.startDate())) {
            throw new ValidationException("endDate must be on or after startDate");
        }
        AcademicTerm term = new AcademicTerm();
        term.setAcademicYear(request.academicYear());
        term.setStartDate(request.startDate());
        term.setEndDate(request.endDate());
        term.setStatus(request.status() != null ? request.status() : TermStatus.ACTIVE);
        return toResponse(termRepository.save(term));
    }

    @Transactional
    public AcademicTermResponse update(UUID termId, AcademicTermRequest request) {
        AcademicTerm term = findTerm(termId);
        if (request.endDate() != null && request.startDate() != null
                && request.endDate().isBefore(request.startDate())) {
            throw new ValidationException("endDate must be on or after startDate");
        }
        term.setAcademicYear(request.academicYear());
        term.setStartDate(request.startDate());
        term.setEndDate(request.endDate());
        if (request.status() != null) {
            term.setStatus(request.status());
        }
        return toResponse(termRepository.save(term));
    }

    @Transactional
    public void delete(UUID termId) {
        findTerm(termId);
        termRepository.deleteById(termId);
    }

    public List<StudentResponse> getStudents(UUID termId) {
        findTerm(termId);
        return studentRepository.findByTerm_TermId(termId).stream().map(StudentService::toResponse).toList();
    }

    public List<TeachingAssignmentResponse> getTeachingAssignments(UUID termId) {
        findTerm(termId);
        return teachingAssignmentRepository.findByTerm_TermId(termId).stream()
                .map(TeachingAssignmentService::toResponse).toList();
    }

    public List<ScheduleResponse> getSchedules(UUID termId) {
        findTerm(termId);
        return classScheduleRepository.findByTermIdWithDetails(termId).stream()
                .map(ClassScheduleService::toResponse).toList();
    }

    public List<ResultBatchResponse> getResults(UUID termId) {
        findTerm(termId);
        return resultBatchRepository.findByTerm_TermId(termId).stream()
                .map(ResultBatchService::toResponse).toList();
    }

    public AcademicTerm findTerm(UUID termId) {
        return termRepository.findById(termId)
                .orElseThrow(() -> new ResourceNotFoundException("Academic term not found"));
    }

    static AcademicTermResponse toResponse(AcademicTerm term) {
        return new AcademicTermResponse(term.getTermId(), term.getAcademicYear(),
                term.getStartDate(), term.getEndDate(), term.getStatus());
    }
}