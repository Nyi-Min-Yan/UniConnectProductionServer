package com.unicconnect.service;

import com.unicconnect.dto.request.CreateTeachingGroupRequest;
import com.unicconnect.dto.response.TeachingGroupResponse;
import com.unicconnect.entity.*;
import com.unicconnect.exception.BusinessRuleException;
import com.unicconnect.exception.DuplicateResourceException;
import com.unicconnect.exception.ResourceNotFoundException;
import com.unicconnect.exception.ValidationException;
import com.unicconnect.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Combined (shared) teaching groups. An HOD bundles the section assignments of a
 * course in a term into ONE group so the timetable generator schedules a single
 * shared slot (e.g. "CS-2256 A + B + C") instead of one slot per section.
 */
@Service
@Transactional(readOnly = true)
public class TeachingAssignmentGroupService {

    private final TeachingAssignmentGroupRepository groupRepository;
    private final TeachingAssignmentGroupMemberRepository memberRepository;
    private final TeachingAssignmentRepository assignmentRepository;
    private final CourseRepository courseRepository;
    private final AcademicTermRepository termRepository;
    private final ClassScheduleRepository scheduleRepository;
    private final HodAccessService hodAccessService;
    private final TimetableRealtimeEventService realtimeEventService;

    public TeachingAssignmentGroupService(TeachingAssignmentGroupRepository groupRepository,
                                          TeachingAssignmentGroupMemberRepository memberRepository,
                                          TeachingAssignmentRepository assignmentRepository,
                                          CourseRepository courseRepository,
                                          AcademicTermRepository termRepository,
                                          ClassScheduleRepository scheduleRepository,
                                          HodAccessService hodAccessService,
                                          TimetableRealtimeEventService realtimeEventService) {
        this.groupRepository = groupRepository;
        this.memberRepository = memberRepository;
        this.assignmentRepository = assignmentRepository;
        this.courseRepository = courseRepository;
        this.termRepository = termRepository;
        this.scheduleRepository = scheduleRepository;
        this.hodAccessService = hodAccessService;
        this.realtimeEventService = realtimeEventService;
    }

    public List<TeachingGroupResponse> getAll(UUID termId) {
        List<TeachingAssignmentGroup> groups = termId != null
                ? groupRepository.findWithCourseByTermId(termId)
                : groupRepository.findAll();
        return groups.stream()
                .map(g -> toResponse(g, memberRepository.findWithDetailsByGroupId(g.getGroupId())))
                .toList();
    }

    public TeachingGroupResponse getById(UUID groupId) {
        TeachingAssignmentGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Teaching group not found"));
        return toResponse(group, memberRepository.findWithDetailsByGroupId(groupId));
    }

    @Transactional
    public TeachingGroupResponse create(CreateTeachingGroupRequest request) {
        hodAccessService.requireHod();
        if (request.assignmentIds() == null || request.assignmentIds().size() < 2) {
            throw new ValidationException("A combined class needs at least two section assignments");
        }
        AcademicTerm term = termRepository.findById(request.termId())
                .orElseThrow(() -> new ResourceNotFoundException("Academic term not found"));
        Course course = courseRepository.findById(request.courseId())
                .orElseThrow(() -> new ResourceNotFoundException("Course not found"));
        hodAccessService.requireHodForUnit(course.getUnit().getUnitId());
        if (groupRepository.existsByTerm_TermIdAndCourse_CourseId(request.termId(), request.courseId())) {
            throw new DuplicateResourceException(
                    "A combined class already exists for this course in the given term");
        }

        List<TeachingAssignment> members = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (UUID id : request.assignmentIds()) {
            if (!seen.add(id)) {
                throw new ValidationException("Duplicate assignment id: " + id);
            }
            TeachingAssignment a = assignmentRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Teaching assignment not found: " + id));
            if (a.getAssignmentStatus() == AssignmentStatus.CANCELLED) {
                throw new ValidationException(
                        "A cancelled assignment cannot be part of a combined class");
            }
            if (!a.getTerm().getTermId().equals(request.termId())) {
                throw new ValidationException(
                        "All assignments of a combined class must belong to the same term");
            }
            if (!a.getCourse().getCourseId().equals(request.courseId())) {
                throw new ValidationException(
                        "All assignments of a combined class must be for the same course");
            }
            if (memberRepository.existsByAssignment_AssignmentId(id)) {
                throw new ValidationException(
                        "Assignment is already part of another combined class");
            }
            members.add(a);
        }

        Set<UUID> sectionIds = members.stream()
                .map(a -> a.getSection().getSectionId())
                .collect(Collectors.toSet());
        if (sectionIds.size() < 2) {
            throw new ValidationException("A combined class must span at least two different sections");
        }

        String groupName = course.getCourseCode() + " (" + members.stream()
                .map(a -> a.getSection().getSectionName())
                .sorted()
                .collect(Collectors.joining(" + ")) + ")";

        TeachingAssignmentGroup group = new TeachingAssignmentGroup();
        group.setTerm(term);
        group.setCourse(course);
        group.setGroupName(groupName);
        for (TeachingAssignment a : members) {
            group.getMembers().add(new TeachingAssignmentGroupMember(group, a));
        }
        TeachingAssignmentGroup saved = groupRepository.save(group);
        TeachingGroupResponse response =
                toResponse(saved, memberRepository.findWithDetailsByGroupId(saved.getGroupId()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("groupId", saved.getGroupId());
        payload.put("termId", saved.getTerm().getTermId());
        payload.put("courseId", saved.getCourse().getCourseId());
        payload.put("courseCode", saved.getCourse().getCourseCode());
        realtimeEventService.publishForTerm(saved.getTerm().getTermId(),
                TimetableRealtimeEventService.TEACHING_GROUP_CREATED, payload);
        return response;
    }

    @Transactional
    public TeachingGroupResponse addMembers(UUID groupId, List<UUID> assignmentIds) {
        TeachingAssignmentGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Teaching group not found"));
        hodAccessService.requireHodForUnit(group.getCourse().getUnit().getUnitId());
        if (assignmentIds == null || assignmentIds.isEmpty()) {
            throw new ValidationException("Provide at least one assignment to add");
        }

        UUID termId = group.getTerm().getTermId();
        UUID courseId = group.getCourse().getCourseId();
        for (UUID id : new LinkedHashSet<>(assignmentIds)) {
            TeachingAssignment a = assignmentRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Teaching assignment not found: " + id));
            if (a.getAssignmentStatus() == AssignmentStatus.CANCELLED) {
                throw new ValidationException(
                        "A cancelled assignment cannot be part of a combined class");
            }
            if (!a.getTerm().getTermId().equals(termId)) {
                throw new ValidationException(
                        "All assignments of a combined class must belong to the same term");
            }
            if (!a.getCourse().getCourseId().equals(courseId)) {
                throw new ValidationException(
                        "All assignments of a combined class must be for the same course");
            }
            if (a.getCourse().getUnit() == null
                    || !a.getCourse().getUnit().getUnitId()
                            .equals(group.getCourse().getUnit().getUnitId())) {
                throw new BusinessRuleException(
                        "A combined class can only include sections from its own department");
            }
            if (memberRepository.existsByAssignment_AssignmentId(id)) {
                throw new ValidationException(
                        "Assignment is already part of another combined class");
            }
            if (group.getMembers().stream()
                    .anyMatch(m -> m.getAssignment().getAssignmentId().equals(id))) {
                throw new ValidationException("Assignment is already in this combined class");
            }
            group.getMembers().add(new TeachingAssignmentGroupMember(group, a));
        }
        TeachingAssignmentGroup saved = groupRepository.save(group);
        TeachingGroupResponse response =
                toResponse(saved, memberRepository.findWithDetailsByGroupId(saved.getGroupId()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("groupId", saved.getGroupId());
        payload.put("termId", saved.getTerm().getTermId());
        payload.put("courseId", saved.getCourse().getCourseId());
        payload.put("courseCode", saved.getCourse().getCourseCode());
        realtimeEventService.publishForTerm(termId,
                TimetableRealtimeEventService.TEACHING_GROUP_UPDATED, payload);
        return response;
    }

    @Transactional
    public void delete(UUID groupId) {
        TeachingAssignmentGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Teaching group not found"));
        hodAccessService.requireHodForUnit(group.getCourse().getUnit().getUnitId());
        if (scheduleRepository.existsByTeachingGroup_GroupId(groupId)) {
            throw new BusinessRuleException(
                    "This combined class is already used by a timetable. Delete or regenerate the "
                            + "timetable first before removing the combined class.");
        }
        UUID termId = group.getTerm().getTermId();
        UUID courseId = group.getCourse().getCourseId();
        String courseCode = group.getCourse().getCourseCode();
        groupRepository.delete(group);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("groupId", groupId);
        payload.put("termId", termId);
        payload.put("courseId", courseId);
        payload.put("courseCode", courseCode);
        realtimeEventService.publishForTerm(termId,
                TimetableRealtimeEventService.TEACHING_GROUP_DELETED, payload);
    }

    static TeachingGroupResponse toResponse(TeachingAssignmentGroup group,
                                            List<TeachingAssignmentGroupMember> members) {
        List<TeachingAssignmentGroupMember> ordered = new ArrayList<>(members);
        ordered.sort(Comparator.comparing(m -> m.getAssignment().getSection().getSectionName()));
        List<TeachingGroupResponse.Member> memberResponses = ordered.stream()
                .map(m -> new TeachingGroupResponse.Member(
                        m.getAssignment().getAssignmentId(),
                        m.getAssignment().getStaff().getStaffId(),
                        m.getAssignment().getStaff().getStaffNo(),
                        m.getAssignment().getStaff().getStaffName(),
                        m.getAssignment().getSection().getSectionId(),
                        m.getAssignment().getSection().getSectionName(),
                        m.getAssignment().getCourse().getUnit().getUnitId(),
                        m.getAssignment().getCourse().getUnit().getUnitName()))
                .toList();
        return new TeachingGroupResponse(
                group.getGroupId(),
                group.getTerm().getTermId(),
                group.getTerm().getAcademicYear(),
                group.getCourse().getCourseId(),
                group.getCourse().getCourseCode(),
                group.getCourse().getCourseName(),
                group.getCourse().getSemester() != null
                        ? group.getCourse().getSemester().getSemesterNo() : null,
                group.getGroupName(),
                group.getCreatedAt(),
                memberResponses);
    }
}
