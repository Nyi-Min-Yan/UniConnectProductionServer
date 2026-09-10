package com.unicconnect.service;

import com.unicconnect.dto.request.MeetingRequirementRequest;
import com.unicconnect.dto.response.MeetingRequirementResponse;
import com.unicconnect.entity.Course;
import com.unicconnect.entity.CourseMeetingRequirement;
import com.unicconnect.exception.ResourceNotFoundException;
import com.unicconnect.exception.ValidationException;
import com.unicconnect.repository.CourseMeetingRequirementRepository;
import com.unicconnect.repository.CourseRepository;
import com.unicconnect.repository.TeachingAssignmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class CourseMeetingRequirementService {

    private final CourseMeetingRequirementRepository requirementRepository;
    private final CourseRepository courseRepository;
    private final HodAccessService hodAccessService;
    private final TimetableRealtimeEventService realtimeEventService;
    private final TeachingAssignmentRepository assignmentRepository;

    public CourseMeetingRequirementService(CourseMeetingRequirementRepository requirementRepository,
                                           CourseRepository courseRepository,
                                           HodAccessService hodAccessService,
                                           TimetableRealtimeEventService realtimeEventService,
                                           TeachingAssignmentRepository assignmentRepository) {
        this.requirementRepository = requirementRepository;
        this.courseRepository = courseRepository;
        this.hodAccessService = hodAccessService;
        this.realtimeEventService = realtimeEventService;
        this.assignmentRepository = assignmentRepository;
    }

    public List<MeetingRequirementResponse> getAll(UUID unitId, UUID semesterId) {
        return requirementRepository.findAll().stream()
                .filter(r -> unitId == null
                        || (r.getCourse().getUnit() != null
                            && r.getCourse().getUnit().getUnitId().equals(unitId)))
                .filter(r -> semesterId == null
                        || (r.getCourse().getSemester() != null
                            && r.getCourse().getSemester().getSemesterId().equals(semesterId)))
                .map(CourseMeetingRequirementService::toResponse).toList();
    }

    public MeetingRequirementResponse getById(UUID requirementId) {
        return toResponse(requirementRepository.findById(requirementId)
                .orElseThrow(() -> new ResourceNotFoundException("Meeting requirement not found")));
    }

    @Transactional
    public MeetingRequirementResponse create(MeetingRequirementRequest request) {
        if (request.courseId() == null) {
            throw new ValidationException("courseId is required");
        }
        Course course = courseRepository.findById(request.courseId())
                .orElseThrow(() -> new ResourceNotFoundException("Course not found"));
        // HODs may only manage courses belonging to their own organizational unit.
        hodAccessService.requireHodForUnit(course.getUnit().getUnitId());
        MeetingRequirementResponse response =
                createInternal(requirementRepository, course, request);
        publishRequirementChanged(response, TimetableRealtimeEventService.COURSE_REQUIREMENT_CREATED);
        return response;
    }

    @Transactional
    public MeetingRequirementResponse update(UUID requirementId, MeetingRequirementRequest request) {
        CourseMeetingRequirement requirement = requirementRepository.findById(requirementId)
                .orElseThrow(() -> new ResourceNotFoundException("Meeting requirement not found"));
        hodAccessService.requireHodForUnit(requirement.getCourse().getUnit().getUnitId());
        MeetingRequirementResponse response = updateInternal(requirementRepository,
                requirement.getCourse().getCourseId(), requirementId, request);
        publishRequirementChanged(response, TimetableRealtimeEventService.COURSE_REQUIREMENT_UPDATED);
        return response;
    }

    @Transactional
    public void delete(UUID requirementId) {
        CourseMeetingRequirement requirement = requirementRepository.findById(requirementId)
                .orElseThrow(() -> new ResourceNotFoundException("Meeting requirement not found"));
        hodAccessService.requireHodForUnit(requirement.getCourse().getUnit().getUnitId());
        requirementRepository.delete(requirement);
        publishRequirementChanged(
                new MeetingRequirementResponse(requirement.getRequirementId(),
                        requirement.getCourse().getCourseId(),
                        requirement.getCourse().getCourseCode(),
                        requirement.getMeetingType(),
                        requirement.getSessionsPerWeek(),
                        requirement.getPeriodsPerSession()),
                TimetableRealtimeEventService.COURSE_REQUIREMENT_DELETED);
    }

    private void publishRequirementChanged(MeetingRequirementResponse requirement, String type) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requirementId", requirement.requirementId());
        payload.put("courseId", requirement.courseId());
        payload.put("courseCode", requirement.courseCode());
        realtimeEventService.publishForCourse(requirement.courseId(), type, payload);
    }

    static MeetingRequirementResponse createInternal(CourseMeetingRequirementRepository repository,
                                                     Course course, MeetingRequirementRequest request) {
        validate(request);
        int total = currentTotalPeriods(repository, course.getCourseId())
                + request.sessionsPerWeek() * request.periodsPerSession();
        if (total > 4) {
            throw new ValidationException(
                    "Total weekly periods for course " + course.getCourseCode()
                            + " would exceed 4 (a course requires exactly 4 weekly periods across all meeting components)");
        }
        CourseMeetingRequirement requirement = new CourseMeetingRequirement();
        requirement.setCourse(course);
        requirement.setMeetingType(request.meetingType());
        requirement.setSessionsPerWeek(request.sessionsPerWeek());
        requirement.setPeriodsPerSession(request.periodsPerSession());
        return toResponse(repository.save(requirement));
    }

    static MeetingRequirementResponse updateInternal(CourseMeetingRequirementRepository repository,
                                                     UUID courseId, UUID requirementId,
                                                     MeetingRequirementRequest request) {
        validate(request);
        CourseMeetingRequirement requirement = repository.findById(requirementId)
                .orElseThrow(() -> new ResourceNotFoundException("Meeting requirement not found"));
        if (!requirement.getCourse().getCourseId().equals(courseId)) {
            throw new ResourceNotFoundException("Meeting requirement not found for this course");
        }
        int others = currentTotalPeriods(repository, courseId)
                - requirement.getSessionsPerWeek() * requirement.getPeriodsPerSession();
        int total = others + request.sessionsPerWeek() * request.periodsPerSession();
        if (total > 4) {
            throw new ValidationException(
                    "Total weekly periods for course " + requirement.getCourse().getCourseCode()
                            + " would exceed 4 (a course requires exactly 4 weekly periods across all meeting components)");
        }
        requirement.setMeetingType(request.meetingType());
        requirement.setSessionsPerWeek(request.sessionsPerWeek());
        requirement.setPeriodsPerSession(request.periodsPerSession());
        return toResponse(repository.save(requirement));
    }

    private static int currentTotalPeriods(CourseMeetingRequirementRepository repository, UUID courseId) {
        return repository.findByCourse_CourseId(courseId).stream()
                .mapToInt(r -> r.getSessionsPerWeek() * r.getPeriodsPerSession())
                .sum();
    }

    private static void validate(MeetingRequirementRequest request) {
        if (request.sessionsPerWeek() == null || request.sessionsPerWeek() <= 0) {
            throw new ValidationException("sessionsPerWeek must be greater than 0");
        }
        if (request.periodsPerSession() == null || request.periodsPerSession() <= 0) {
            throw new ValidationException("periodsPerSession must be greater than 0");
        }
    }

    static MeetingRequirementResponse toResponse(CourseMeetingRequirement requirement) {
        return new MeetingRequirementResponse(
                requirement.getRequirementId(),
                requirement.getCourse().getCourseId(),
                requirement.getCourse().getCourseCode(),
                requirement.getMeetingType(),
                requirement.getSessionsPerWeek(),
                requirement.getPeriodsPerSession());
    }
}