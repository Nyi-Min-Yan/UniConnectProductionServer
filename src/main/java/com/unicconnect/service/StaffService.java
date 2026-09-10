package com.unicconnect.service;

import com.unicconnect.dto.request.CreateStaffUserRequest;
import com.unicconnect.dto.request.StaffPositionAssignmentRequest;
import com.unicconnect.dto.request.StaffRequest;
import com.unicconnect.dto.response.AssignedCourseResponse;
import com.unicconnect.dto.response.LecturerResponse;
import com.unicconnect.dto.response.SectionInfoResponse;
import com.unicconnect.dto.response.StaffPositionAssignmentResponse;
import com.unicconnect.dto.response.StaffResponse;
import com.unicconnect.dto.response.TeachingAssignmentResponse;
import com.unicconnect.entity.*;
import com.unicconnect.exception.BusinessRuleException;
import com.unicconnect.exception.DuplicateResourceException;
import com.unicconnect.exception.ResourceNotFoundException;
import com.unicconnect.exception.ValidationException;
import com.unicconnect.repository.*;
import com.unicconnect.util.SecurityUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class StaffService {

    private final StaffRepository staffRepository;
    private final UserRepository userRepository;
    private final PositionRepository positionRepository;
    private final StaffPositionAssignmentRepository assignmentRepository;
    private final TeachingAssignmentRepository teachingAssignmentRepository;
    private final OrganizationalUnitRepository unitRepository;
    private final AcademicTermRepository termRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityUtil securityUtil;

    public StaffService(StaffRepository staffRepository,
                        UserRepository userRepository,
                        PositionRepository positionRepository,
                        StaffPositionAssignmentRepository assignmentRepository,
                        TeachingAssignmentRepository teachingAssignmentRepository,
                        OrganizationalUnitRepository unitRepository,
                        AcademicTermRepository termRepository,
                        RoleRepository roleRepository,
                        PasswordEncoder passwordEncoder,
                        SecurityUtil securityUtil) {
        this.staffRepository = staffRepository;
        this.userRepository = userRepository;
        this.positionRepository = positionRepository;
        this.assignmentRepository = assignmentRepository;
        this.teachingAssignmentRepository = teachingAssignmentRepository;
        this.unitRepository = unitRepository;
        this.termRepository = termRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.securityUtil = securityUtil;
    }

    public List<StaffResponse> getAll() {
        List<Staff> staff = staffRepository.findAllWithUserAndUnit();
        Map<UUID, List<StaffPositionAssignment>> assignmentsByStaff = assignmentRepository
                .findAllWithPositionAndStaff().stream()
                .collect(Collectors.groupingBy(pa -> pa.getStaff().getStaffId()));
        return staff.stream()
                .map(s -> toResponse(s, assignmentsByStaff.getOrDefault(s.getStaffId(), java.util.Collections.emptyList())))
                .toList();
    }

    public StaffResponse getById(UUID staffId) {
        Staff staff = findStaff(staffId);
        return toResponse(staff, assignmentRepository.findByStaff_StaffId(staffId));
    }

    public StaffResponse getCurrentStaff() {
        Staff staff = staffRepository.findByUser_UserId(securityUtil.currentUserId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No staff profile found for the current user"));
        return toResponse(staff, assignmentRepository.findByStaff_StaffId(staff.getStaffId()));
    }

    @Transactional
    public StaffResponse create(StaffRequest request) {
        if (staffRepository.existsByStaffNo(request.staffNo())) {
            throw new DuplicateResourceException("Staff number already exists: " + request.staffNo());
        }
        if (staffRepository.existsByUser_UserId(request.userId())) {
            throw new DuplicateResourceException("A staff profile already exists for this user");
        }
        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Staff staff = new Staff();
        apply(staff, request, user);
        return toResponse(staffRepository.save(staff), List.of());
    }

    @Transactional
    public StaffResponse createWithUser(CreateStaffUserRequest request) {
        if (userRepository.existsByEmail(request.email().toLowerCase())) {
            throw new DuplicateResourceException("Email is already in use");
        }
        List<String> positionNames = validatePositions(request.positionNames());
        String staffNo = request.staffNo() != null && !request.staffNo().isBlank()
                ? request.staffNo().toUpperCase() : nextStaffNo();
        if (staffRepository.existsByStaffNo(staffNo)) {
            throw new DuplicateResourceException("Staff number already exists: " + staffNo);
        }

        User user = new User();
        user.setEmail(request.email().toLowerCase());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(roleRepository.findByRoleName("STAFF")
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: STAFF")));
        user.setActive(request.isActive() == null || request.isActive());
        user.setRegistrationStatus(RegistrationStatus.APPROVED);
        user = userRepository.save(user);

        Staff staff = new Staff();
        staff.setUser(user);
        staff.setStaffNo(staffNo);
        staff.setStaffName(request.staffName());
        staff.setPhoneNo(request.phoneNo());
        staff.setBatchYear(request.batchYear());
        staff.setAddress(request.address());
        staff.setUnit(unitRepository.findById(request.unitId())
                .orElseThrow(() -> new ResourceNotFoundException("Organizational unit not found")));
        staff.setJoinedAt(request.joinedAt());
        staff = staffRepository.save(staff);

        LocalDate startDate = request.joinedAt() != null ? request.joinedAt() : LocalDate.now();
        Staff assigner = staffRepository.findByUser_UserId(securityUtil.currentUserId()).orElse(null);
        List<StaffPositionAssignment> assignments = new ArrayList<>();
        for (String name : positionNames) {
            assignments.add(assignmentRepository.save(createAssignment(staff, name, startDate, assigner)));
        }
        return toResponse(staff, assignments);
    }

    private StaffPositionAssignment createAssignment(Staff staff, String positionName, LocalDate startDate,
                                                     Staff assigner) {
        Position position = positionRepository.findByPositionName(positionName)
                .orElseThrow(() -> new ResourceNotFoundException("Position not found: " + positionName));
        enforcePositionRules(staff, positionName);
        if (assignmentRepository.existsByStaff_StaffIdAndPosition_PositionIdAndStartDate(
                staff.getStaffId(), position.getPositionId(), startDate)) {
            throw new DuplicateResourceException("This staff member already has this position starting on "
                    + startDate);
        }
        StaffPositionAssignment assignment = new StaffPositionAssignment();
        assignment.setStaff(staff);
        assignment.setPosition(position);
        assignment.setStartDate(startDate);
        assignment.setAssignedByStaff(assigner);
        return assignment;
    }

    /**
     * Lecturers and HODs must belong to an academic unit, and at most one active
     * HOD is allowed per department or faculty.
     */
    private void enforcePositionRules(Staff staff, String positionName) {
        if ("HOD".equals(positionName)) {
            enforceAcademicUnit(staff, "HODs");
            LocalDate today = LocalDate.now();
            boolean otherHodInUnit = staff.getUnit() != null
                    && staffRepository.findByUnit_UnitId(staff.getUnit().getUnitId()).stream()
                            .filter(other -> !other.getStaffId().equals(staff.getStaffId()))
                            .anyMatch(other -> assignmentRepository
                                    .findByStaff_StaffId(other.getStaffId()).stream()
                                    .anyMatch(pa -> "HOD".equals(pa.getPosition().getPositionName())
                                            && !pa.getStartDate().isAfter(today)
                                            && (pa.getEndDate() == null || !pa.getEndDate().isBefore(today))));
            if (otherHodInUnit) {
                throw new BusinessRuleException(
                        "This department already has an active HOD");
            }
        } else if ("LECTURER".equals(positionName)) {
            enforceAcademicUnit(staff, "Lecturers");
        }
    }

    private void enforceAcademicUnit(Staff staff, String entityLabel) {
        if (staff.getUnit() == null || !"ACADEMIC".equalsIgnoreCase(staff.getUnit().getUnitType())) {
            throw new ValidationException(
                    entityLabel + " must belong to an academic unit (department or faculty)");
        }
    }

    private static final Map<String, java.util.Set<String>> POSITION_RULES = Map.of(
            "LECTURER", java.util.Set.of("HOD"),
            "STUDENT_AFFAIRS_OFFICER", java.util.Set.of("HOD", "JUNIOR_CLERK", "SENIOR_CLERK"),
            "FINANCE_OFFICER", java.util.Set.of("HOD", "JUNIOR_CLERK", "SENIOR_CLERK"),
            "ADMINISTRATIVE_OFFICER", java.util.Set.of("HOD", "JUNIOR_CLERK", "SENIOR_CLERK",
                    "RECTOR", "PRO_RECTOR"));

    private List<String> validatePositions(List<String> positionNames) {
        List<String> names = positionNames.stream().map(String::toUpperCase).toList();
        String base = names.stream().filter(POSITION_RULES::containsKey).findFirst()
                .orElseThrow(() -> new ValidationException(
                        "One default position (LECTURER, STUDENT_AFFAIRS_OFFICER, FINANCE_OFFICER "
                                + "or ADMINISTRATIVE_OFFICER) is required"));
        if (names.stream().filter(POSITION_RULES::containsKey).count() > 1) {
            throw new ValidationException("Only one default position may be assigned");
        }
        java.util.Set<String> allowed = POSITION_RULES.get(base);
        for (String name : names) {
            if (!POSITION_RULES.containsKey(name) && !allowed.contains(name)) {
                throw new ValidationException("Position " + name + " is not allowed for a " + base);
            }
        }
        if (names.stream().distinct().count() != names.size()) {
            throw new ValidationException("Duplicate position in request");
        }
        return names;
    }

    private String nextStaffNo() {
        String prefix = "STF";
        int max = 0;
        for (Staff existing : staffRepository.findAll()) {
            String no = existing.getStaffNo();
            if (no != null && no.startsWith(prefix) && no.substring(prefix.length()).matches("\\d+")) {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            }
        }
        String candidate;
        do {
            max++;
            candidate = prefix + String.format("%03d", max);
        } while (staffRepository.existsByStaffNo(candidate));
        return candidate;
    }

    @Transactional
    public StaffResponse update(UUID staffId, StaffRequest request) {
        Staff staff = findStaff(staffId);
        if (!staff.getStaffNo().equals(request.staffNo()) && staffRepository.existsByStaffNo(request.staffNo())) {
            throw new DuplicateResourceException("Staff number already exists: " + request.staffNo());
        }
        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        apply(staff, request, user);
        boolean hasTeachingPosition = assignmentRepository.findByStaff_StaffId(staffId).stream()
                .map(pa -> pa.getPosition().getPositionName())
                .anyMatch(name -> "LECTURER".equals(name) || "HOD".equals(name));
        if (hasTeachingPosition) {
            enforceAcademicUnit(staff, "Lecturers and HODs");
        }
        if (request.positionNames() != null && !request.positionNames().isEmpty()) {
            syncPositionAssignments(staff, request.positionNames());
        }
        staff = staffRepository.save(staff);
        return toResponse(staff, assignmentRepository.findByStaff_StaffId(staffId));
    }

    private void syncPositionAssignments(Staff staff, List<String> positionNames) {
        List<String> names = validatePositions(positionNames);
        assignmentRepository.deleteAll(assignmentRepository.findByStaff_StaffId(staff.getStaffId()));
        assignmentRepository.flush();
        LocalDate startDate = staff.getJoinedAt() != null ? staff.getJoinedAt() : LocalDate.now();
        Staff assigner = staffRepository.findByUser_UserId(securityUtil.currentUserId()).orElse(null);
        for (String name : names) {
            assignmentRepository.save(createAssignment(staff, name, startDate, assigner));
        }
    }

    @Transactional
    public void delete(UUID staffId) {
        findStaff(staffId);
        staffRepository.deleteById(staffId);
    }

    private void apply(Staff staff, StaffRequest request, User user) {
        staff.setUser(user);
        staff.setStaffNo(request.staffNo());
        staff.setStaffName(request.staffName());
        staff.setPhoneNo(request.phoneNo());
        staff.setBatchYear(request.batchYear());
        staff.setAddress(request.address());
        if (request.unitId() != null) {
            staff.setUnit(unitRepository.findById(request.unitId())
                    .orElseThrow(() -> new ResourceNotFoundException("Organizational unit not found")));
        } else {
            staff.setUnit(null);
        }
        staff.setJoinedAt(request.joinedAt());
        staff.setLeftDate(request.leftDate());
    }

    // ---------- Position assignments ----------

    public List<StaffPositionAssignmentResponse> getPositionAssignments(UUID staffId) {
        findStaff(staffId);
        return assignmentRepository.findByStaff_StaffId(staffId).stream()
                .map(StaffService::toResponse).toList();
    }

    @Transactional
    public StaffPositionAssignmentResponse assignPosition(UUID staffId, StaffPositionAssignmentRequest request) {
        Staff staff = findStaff(staffId);
        Position position = positionRepository.findById(request.positionId())
                .orElseThrow(() -> new ResourceNotFoundException("Position not found"));
        enforcePositionRules(staff, position.getPositionName());

        if (assignmentRepository.existsByStaff_StaffIdAndPosition_PositionIdAndStartDate(
                staffId, position.getPositionId(), request.startDate())) {
            throw new DuplicateResourceException(
                    "This staff member already has this position starting on " + request.startDate());
        }
        if (request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            throw new ValidationException("endDate must be on or after startDate");
        }

        StaffPositionAssignment assignment = new StaffPositionAssignment();
        assignment.setStaff(staff);
        assignment.setPosition(position);
        assignment.setStartDate(request.startDate());
        assignment.setEndDate(request.endDate());
        if (request.assignedByStaffId() != null) {
            assignment.setAssignedByStaff(findStaff(request.assignedByStaffId()));
        }
        return toResponse(assignmentRepository.save(assignment));
    }

    @Transactional
    public StaffPositionAssignmentResponse updatePositionAssignment(UUID staffId, UUID assignmentId,
                                                                    StaffPositionAssignmentRequest request) {
        StaffPositionAssignment assignment = assignmentRepository
                .findByStaff_StaffIdAndPositionAssignmentId(staffId, assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Position assignment not found"));

        if (request.endDate() != null && request.endDate().isBefore(assignment.getStartDate())) {
            throw new ValidationException("endDate must be on or after startDate");
        }
        Position position = positionRepository.findById(request.positionId())
                .orElseThrow(() -> new ResourceNotFoundException("Position not found"));
        enforcePositionRules(assignment.getStaff(), position.getPositionName());
        assignment.setPosition(position);
        assignment.setEndDate(request.endDate());
        if (request.assignedByStaffId() != null) {
            assignment.setAssignedByStaff(findStaff(request.assignedByStaffId()));
        }
        return toResponse(assignmentRepository.save(assignment));
    }

    @Transactional
    public void removePositionAssignment(UUID staffId, UUID assignmentId) {
        StaffPositionAssignment assignment = assignmentRepository
                .findByStaff_StaffIdAndPositionAssignmentId(staffId, assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Position assignment not found"));
        assignmentRepository.delete(assignment);
    }

    // ---------- Lecturers ----------

    public List<LecturerResponse> getLecturers(UUID termId) {
        AcademicTerm term = resolveTerm(termId);
        List<Staff> staff = staffRepository.findAllWithUserAndUnit();
        Map<UUID, List<StaffPositionAssignment>> positionsByStaff = assignmentRepository
                .findAllWithPositionAndStaff().stream()
                .collect(Collectors.groupingBy(pa -> pa.getStaff().getStaffId()));
        Map<UUID, List<TeachingAssignment>> assignmentsByStaff = teachingAssignmentRepository
                .findWithDetailsByTermId(term.getTermId()).stream()
                .collect(Collectors.groupingBy(ta -> ta.getStaff().getStaffId()));
        return staff.stream()
                .map(s -> toLecturerResponse(s,
                        positionsByStaff.getOrDefault(s.getStaffId(), java.util.Collections.emptyList()),
                        assignmentsByStaff.getOrDefault(s.getStaffId(), java.util.Collections.emptyList())))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(LecturerResponse::staffNo))
                .toList();
    }

    private AcademicTerm resolveTerm(UUID termId) {
        if (termId != null) {
            return termRepository.findById(termId)
                    .orElseThrow(() -> new ResourceNotFoundException("Academic term not found"));
        }
        return termRepository.findByStatus(TermStatus.ACTIVE).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("No active academic term found"));
    }

    private LecturerResponse toLecturerResponse(Staff staff,
                                                List<StaffPositionAssignment> positionAssignments,
                                                List<TeachingAssignment> assignments) {
        LocalDate today = LocalDate.now();
        List<String> activePositions = positionAssignments.stream()
                .filter(pa -> !pa.getStartDate().isAfter(today)
                        && (pa.getEndDate() == null || !pa.getEndDate().isBefore(today)))
                .map(pa -> pa.getPosition().getPositionName())
                .distinct()
                .toList();
        if (!activePositions.contains("LECTURER")) {
            return null;
        }
        if (!"STAFF".equalsIgnoreCase(staff.getUser().getRole().getRoleName())) {
            return null;
        }

        Map<UUID, AssignedCourseResponse> byCourse = new LinkedHashMap<>();
        for (TeachingAssignment ta : assignments) {
            Course course = ta.getCourse();
            AssignedCourseResponse courseResponse = byCourse.computeIfAbsent(course.getCourseId(), cid ->
                    new AssignedCourseResponse(course.getCourseId(), course.getCourseCode(), course.getCourseName(),
                            course.getSemester() != null ? course.getSemester().getSemesterId() : null,
                            course.getSemester() != null ? course.getSemester().getSemesterNo() : null,
                            new ArrayList<>()));
            courseResponse.sections().add(new SectionInfoResponse(
                    ta.getSection().getSectionId(), ta.getSection().getSectionName()));
        }
        List<AssignedCourseResponse> assignedCourses = byCourse.values().stream()
                .map(c -> new AssignedCourseResponse(c.courseId(), c.courseCode(), c.courseName(),
                        c.semesterId(), c.semesterNo(),
                        c.sections().stream()
                                .sorted(Comparator.comparing(SectionInfoResponse::sectionName))
                                .toList()))
                .sorted(Comparator.comparing(AssignedCourseResponse::courseCode))
                .toList();

        return new LecturerResponse(
                staff.getStaffId(), staff.getStaffNo(), staff.getStaffName(),
                staff.getUser().getEmail(), staff.getPhoneNo(),
                staff.getUnit() != null ? staff.getUnit().getUnitId() : null,
                staff.getUnit() != null ? staff.getUnit().getUnitName() : null,
                activePositions,
                assignedCourses.size(),
                assignedCourses);
    }

    // ---------- Teaching assignments ----------

    public List<TeachingAssignmentResponse> getTeachingAssignments(UUID staffId) {
        findStaff(staffId);
        return teachingAssignmentRepository.findByStaff_StaffId(staffId).stream()
                .map(TeachingAssignmentService::toResponse).toList();
    }

    public Staff findStaff(UUID staffId) {
        return staffRepository.findById(staffId)
                .orElseThrow(() -> new ResourceNotFoundException("Staff member not found"));
    }

    static StaffResponse toResponse(Staff staff, List<StaffPositionAssignment> positionAssignments) {
        List<String> positions = positionAssignments.stream()
                .map(pa -> pa.getPosition().getPositionName())
                .toList();
        return new StaffResponse(
                staff.getStaffId(), staff.getUser().getUserId(),
                staff.getStaffNo(), staff.getStaffName(), staff.getPhoneNo(),
                staff.getBatchYear(), staff.getAddress(),
                staff.getUnit() != null ? staff.getUnit().getUnitId() : null,
                staff.getUnit() != null ? staff.getUnit().getUnitName() : null,
                staff.getJoinedAt(), staff.getLeftDate(), staff.getCreatedAt(),
                positions);
    }

    static StaffPositionAssignmentResponse toResponse(StaffPositionAssignment assignment) {
        return new StaffPositionAssignmentResponse(
                assignment.getPositionAssignmentId(),
                assignment.getStaff().getStaffId(),
                assignment.getPosition().getPositionId(),
                assignment.getPosition().getPositionName(),
                assignment.getStartDate(),
                assignment.getEndDate(),
                assignment.getAssignedByStaff() != null ? assignment.getAssignedByStaff().getStaffId() : null);
    }
}