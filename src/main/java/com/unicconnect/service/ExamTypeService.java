package com.unicconnect.service;

import com.unicconnect.dto.request.ExamTypeRequest;
import com.unicconnect.dto.response.ExamTypeResponse;
import com.unicconnect.entity.ExamType;
import com.unicconnect.exception.DuplicateResourceException;
import com.unicconnect.exception.ResourceNotFoundException;
import com.unicconnect.repository.ExamTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ExamTypeService {

    private final ExamTypeRepository examTypeRepository;

    public ExamTypeService(ExamTypeRepository examTypeRepository) {
        this.examTypeRepository = examTypeRepository;
    }

    public List<ExamTypeResponse> getAll() {
        return examTypeRepository.findAll().stream().map(ExamTypeService::toResponse).toList();
    }

    public ExamTypeResponse getById(UUID examTypeId) {
        return toResponse(findExamType(examTypeId));
    }

    @Transactional
    public ExamTypeResponse create(ExamTypeRequest request) {
        if (examTypeRepository.existsByExamTypeName(request.examTypeName())) {
            throw new DuplicateResourceException("Exam type already exists: " + request.examTypeName());
        }
        ExamType examType = new ExamType();
        examType.setExamTypeName(request.examTypeName());
        return toResponse(examTypeRepository.save(examType));
    }

    @Transactional
    public ExamTypeResponse update(UUID examTypeId, ExamTypeRequest request) {
        ExamType examType = findExamType(examTypeId);
        if (!examType.getExamTypeName().equals(request.examTypeName())
                && examTypeRepository.existsByExamTypeName(request.examTypeName())) {
            throw new DuplicateResourceException("Exam type already exists: " + request.examTypeName());
        }
        examType.setExamTypeName(request.examTypeName());
        return toResponse(examTypeRepository.save(examType));
    }

    @Transactional
    public void delete(UUID examTypeId) {
        findExamType(examTypeId);
        examTypeRepository.deleteById(examTypeId);
    }

    public ExamType findExamType(UUID examTypeId) {
        return examTypeRepository.findById(examTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam type not found"));
    }

    static ExamTypeResponse toResponse(ExamType examType) {
        return new ExamTypeResponse(examType.getExamTypeId(), examType.getExamTypeName());
    }
}
