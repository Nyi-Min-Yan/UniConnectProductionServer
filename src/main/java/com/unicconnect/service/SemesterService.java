package com.unicconnect.service;

import com.unicconnect.dto.response.SemesterResponse;
import com.unicconnect.entity.Semester;
import com.unicconnect.exception.ResourceNotFoundException;
import com.unicconnect.repository.SemesterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class SemesterService {

    private final SemesterRepository semesterRepository;

    public SemesterService(SemesterRepository semesterRepository) {
        this.semesterRepository = semesterRepository;
    }

    public List<SemesterResponse> getAll() {
        return semesterRepository.findAll().stream().map(SemesterService::toResponse).toList();
    }

    public SemesterResponse getById(UUID semesterId) {
        return toResponse(findSemester(semesterId));
    }

    public Semester findSemester(UUID semesterId) {
        return semesterRepository.findById(semesterId)
                .orElseThrow(() -> new ResourceNotFoundException("Semester not found"));
    }

    static SemesterResponse toResponse(Semester semester) {
        return new SemesterResponse(semester.getSemesterId(), semester.getSemesterNo());
    }
}