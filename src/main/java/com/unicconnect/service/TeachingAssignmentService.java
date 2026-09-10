package com.unicconnect.service;

import com.unicconnect.dto.request.TeachingAssignmentRequest;
import com.unicconnect.dto.response.TeachingAssignmentResponse;
import com.unicconnect.entity.TeachingAssignment;
import com.unicconnect.exception.DuplicateResourceException;
import com.unicconnect.exception.ResourceNotFoundException;
import com.unicconnect.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class TeachingAssignmentService {

    private final TeachingAssignmentRepository assignmentRepository;
    private final CourseRepository courseRepository;
    private final StaffRepository staffRepository;
    private final SectionRepository sectionRepository;
    private final AcademicTermRepository termRepository;

    public TeachingAssignmentService(TeachingAssignmentRepository assignmentRepository,
                                     CourseRepository courseRepository,
                                     StaffRepository staffRepository,
                                     SectionRepository sectionRepository,
                                     AcademicTermRepository termRepository) {
        this.assignmentRepository = assignmentRepository;
        this.courseRepository = courseRepository;
        this.staffRepository = staffRepository;
        this.sectionRepository = sectionRepository;
        this.termRepository = termRepository;
    }

    public List<TeachingAssignmentResponse> getAll(UUID termId, UUID staffId, UUID courseId, UUID sectionId) {
        List<TeachingAssignment> assignments;
        if (termId != null) {
            assignments = assignmentRepository.findWithDetailsByTermId(termId);
        } else if (staffId != null) {
            assignments = assignmentRepository.findByStaff_StaffId(staffId);
        } else if (courseId != null) {
            assignments = assignmentRepository.findByCourse_CourseId(courseId);
        } else if (sectionId != null) {
            assignments = assignmentRepository.findBySection_SectionId(sectionId);
        } else {
            assignments = assignmentRepository.findAll();
        }
        return assignments.stream().map(TeachingAssignmentService::toResponse).toList();
    }

    public TeachingAssignmentResponse getById(UUID assignmentId) {
        return toResponse(findAssignment(assignmentId));
    }

    @Transactional
    public TeachingAssignmentResponse create(TeachingAssignmentRequest request) {
        validateRefs(request);
        if (assignmentRepository.existsByTerm_TermIdAndCourse_CourseIdAndSection_SectionId(
                request.termId(), request.courseId(), request.sectionId())) {
            throw new DuplicateResourceException(
                    "This course is already assigned to this section in the given term");
        }
        TeachingAssignment assignment = new TeachingAssignment();
        apply(assignment, request);
        return toResponse(assignmentRepository.save(assignment));
    }

    @Transactional
    public TeachingAssignmentResponse update(UUID assignmentId, TeachingAssignmentRequest request) {
        TeachingAssignment assignment = findAssignment(assignmentId);
        validateRefs(request);
        if ((!assignment.getTerm().getTermId().equals(request.termId())
                || !assignment.getCourse().getCourseId().equals(request.courseId())
                || !assignment.getSection().getSectionId().equals(request.sectionId()))
                && assignmentRepository.existsByTerm_TermIdAndCourse_CourseIdAndSection_SectionId(
                        request.termId(), request.courseId(), request.sectionId())) {
            throw new DuplicateResourceException(
                    "This course is already assigned to this section in the given term");
        }
        apply(assignment, request);
        return toResponse(assignmentRepository.save(assignment));
    }

    @Transactional
    public void delete(UUID assignmentId) {
        findAssignment(assignmentId);
        assignmentRepository.deleteById(assignmentId);
    }

    private void validateRefs(TeachingAssignmentRequest request) {
        courseRepository.findById(request.courseId())
                .orElseThrow(() -> new ResourceNotFoundException("Course not found"));
        staffRepository.findById(request.staffId())
                .orElseThrow(() -> new ResourceNotFoundException("Staff not found"));
        sectionRepository.findById(request.sectionId())
                .orElseThrow(() -> new ResourceNotFoundException("Section not found"));
        termRepository.findById(request.termId())
                .orElseThrow(() -> new ResourceNotFoundException("Academic term not found"));
        if (request.assignedByStaffId() != null) {
            staffRepository.findById(request.assignedByStaffId())
                    .orElseThrow(() -> new ResourceNotFoundException("Assigning staff member not found"));
        }
    }

    private void apply(TeachingAssignment assignment, TeachingAssignmentRequest request) {
        assignment.setCourse(courseRepository.findById(request.courseId()).orElseThrow());
        assignment.setStaff(staffRepository.findById(request.staffId()).orElseThrow());
        assignment.setSection(sectionRepository.findById(request.sectionId()).orElseThrow());
        assignment.setTerm(termRepository.findById(request.termId()).orElseThrow());
        if (request.assignmentStatus() != null) {
            assignment.setAssignmentStatus(request.assignmentStatus());
        }
        if (request.assignedByStaffId() != null) {
            assignment.setAssignedByStaff(staffRepository.findById(request.assignedByStaffId()).orElseThrow());
        }
    }

    public TeachingAssignment findAssignment(UUID assignmentId) {
        return assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Teaching assignment not found"));
    }

    static TeachingAssignmentResponse toResponse(TeachingAssignment assignment) {
        return new TeachingAssignmentResponse(
                assignment.getAssignmentId(),
                assignment.getCourse().getCourseId(),
                assignment.getCourse().getCourseCode(),
                assignment.getCourse().getCourseName(),
                assignment.getStaff().getStaffId(),
                assignment.getStaff().getStaffNo(),
                assignment.getStaff().getStaffName(),
                assignment.getStaff().getUser().getEmail(),
                assignment.getStaff().getUnit() != null
                        ? assignment.getStaff().getUnit().getUnitId() : null,
                assignment.getStaff().getUnit() != null
                        ? assignment.getStaff().getUnit().getUnitName() : null,
                assignment.getSection().getSectionId(),
                assignment.getSection().getSectionName(),
                assignment.getTerm().getTermId(),
                assignment.getTerm().getAcademicYear(),
                assignment.getAssignmentStatus(),
                assignment.getAssignedAt(),
                assignment.getAssignedByStaff() != null
                        ? assignment.getAssignedByStaff().getStaffId() : null);
    }
}