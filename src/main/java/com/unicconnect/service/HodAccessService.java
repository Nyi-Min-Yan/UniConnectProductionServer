package com.unicconnect.service;

import com.unicconnect.entity.Staff;
import com.unicconnect.entity.StaffPositionAssignment;
import com.unicconnect.exception.BusinessRuleException;
import com.unicconnect.repository.StaffPositionAssignmentRepository;
import com.unicconnect.repository.StaffRepository;
import com.unicconnect.util.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared authorization guard for HOD-only actions (timetable generation,
 * course meeting requirement management). Mirrors the lobby rule: the current
 * user must be an active staff member holding both HOD and LECTURER positions.
 */
@Service
@Transactional
public class HodAccessService {

    private final SecurityUtil securityUtil;
    private final StaffRepository staffRepository;
    private final StaffPositionAssignmentRepository positionAssignmentRepository;

    public HodAccessService(SecurityUtil securityUtil,
                            StaffRepository staffRepository,
                            StaffPositionAssignmentRepository positionAssignmentRepository) {
        this.securityUtil = securityUtil;
        this.staffRepository = staffRepository;
        this.positionAssignmentRepository = positionAssignmentRepository;
    }

    public Staff requireHod() {
        Staff staff = staffRepository.findByUser_UserId(securityUtil.currentUserId())
                .orElseThrow(() -> new BusinessRuleException(
                        "Only staff can perform this action"));
        if (!isActiveHodLecturer(staff)) {
            throw new BusinessRuleException(
                    "Only HOD lecturers can perform this action");
        }
        return staff;
    }

    /**
     * Current authenticated staff member when they hold an active HOD+LECTURER
     * assignment, or empty otherwise. Never throws.
     */
    public Optional<Staff> currentHod() {
        return staffRepository.findByUser_UserId(securityUtil.currentUserId())
                .filter(this::isActiveHodLecturer);
    }

    /**
     * HOD guard scoped to a single organizational unit: the caller must be an
     * active HOD lecturer whose {@code staff.unit_id} equals the given unit.
     * Used to authorize course-meeting-requirement mutations without trusting a
     * unit id sent by the frontend.
     */
    public Staff requireHodForUnit(java.util.UUID unitId) {
        Staff staff = requireHod();
        if (staff.getUnit() == null || !staff.getUnit().getUnitId().equals(unitId)) {
            throw new BusinessRuleException(
                    "You can only manage courses in your own department");
        }
        return staff;
    }

    private boolean isActiveHodLecturer(Staff staff) {
        LocalDate today = LocalDate.now();
        Set<String> active = positionAssignmentRepository
                .findByStaff_StaffId(staff.getStaffId()).stream()
                .filter(pa -> isActiveAssignment(pa, today))
                .map(pa -> pa.getPosition().getPositionName())
                .collect(Collectors.toSet());
        return active.contains("HOD") && active.contains("LECTURER");
    }

    private boolean isActiveAssignment(StaffPositionAssignment pa, LocalDate today) {
        return !pa.getStartDate().isAfter(today)
                && (pa.getEndDate() == null || !pa.getEndDate().isBefore(today));
    }
}
