package com.unicconnect.service;

import com.unicconnect.dto.request.MajorRequest;
import com.unicconnect.dto.response.CourseResponse;
import com.unicconnect.dto.response.MajorResponse;
import com.unicconnect.dto.response.StudentResponse;
import com.unicconnect.entity.Major;
import com.unicconnect.exception.ResourceNotFoundException;
import com.unicconnect.repository.CourseRepository;
import com.unicconnect.repository.MajorRepository;
import com.unicconnect.repository.StudentRepository;
import com.unicconnect.repository.OrganizationalUnitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class MajorService {

    private final MajorRepository majorRepository;
    private final OrganizationalUnitRepository unitRepository;
    private final CourseRepository courseRepository;
    private final StudentRepository studentRepository;

    public MajorService(MajorRepository majorRepository,
                        OrganizationalUnitRepository unitRepository,
                        CourseRepository courseRepository,
                        StudentRepository studentRepository) {
        this.majorRepository = majorRepository;
        this.unitRepository = unitRepository;
        this.courseRepository = courseRepository;
        this.studentRepository = studentRepository;
    }

    public List<MajorResponse> getAll() {
        return majorRepository.findAll().stream().map(MajorService::toResponse).toList();
    }

    public MajorResponse getById(UUID majorId) {
        return toResponse(findMajor(majorId));
    }

    @Transactional
    public MajorResponse create(MajorRequest request) {
        Major major = new Major();
        major.setUnit(unitRepository.findById(request.unitId())
                .orElseThrow(() -> new ResourceNotFoundException("Organizational unit not found")));
        major.setMajorCode(request.majorCode());
        major.setMajorName(request.majorName());
        return toResponse(majorRepository.save(major));
    }

    @Transactional
    public MajorResponse update(UUID majorId, MajorRequest request) {
        Major major = findMajor(majorId);
        major.setUnit(unitRepository.findById(request.unitId())
                .orElseThrow(() -> new ResourceNotFoundException("Organizational unit not found")));
        major.setMajorCode(request.majorCode());
        major.setMajorName(request.majorName());
        return toResponse(majorRepository.save(major));
    }

    @Transactional
    public void delete(UUID majorId) {
        findMajor(majorId);
        majorRepository.deleteById(majorId);
    }

    public List<CourseResponse> getCourses(UUID majorId) {
        findMajor(majorId);
        return courseRepository.findByMajor_MajorId(majorId).stream().map(CourseService::toResponse).toList();
    }

    public List<StudentResponse> getStudents(UUID majorId) {
        findMajor(majorId);
        return studentRepository.findByMajor_MajorId(majorId).stream().map(StudentService::toResponse).toList();
    }

    public Major findMajor(UUID majorId) {
        return majorRepository.findById(majorId)
                .orElseThrow(() -> new ResourceNotFoundException("Major not found"));
    }

    static MajorResponse toResponse(Major major) {
        return new MajorResponse(major.getMajorId(),
                major.getUnit().getUnitId(), major.getUnit().getUnitCode(),
                major.getMajorCode(), major.getMajorName());
    }
}