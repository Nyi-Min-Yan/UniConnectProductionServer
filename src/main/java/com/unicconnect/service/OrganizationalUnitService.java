package com.unicconnect.service;

import com.unicconnect.dto.request.OrganizationalUnitRequest;
import com.unicconnect.dto.response.CourseResponse;
import com.unicconnect.dto.response.MajorResponse;
import com.unicconnect.dto.response.OrganizationalUnitResponse;
import com.unicconnect.dto.response.StaffResponse;
import com.unicconnect.entity.OrganizationalUnit;
import com.unicconnect.exception.DuplicateResourceException;
import com.unicconnect.exception.ResourceNotFoundException;
import com.unicconnect.repository.CourseRepository;
import com.unicconnect.repository.MajorRepository;
import com.unicconnect.repository.OrganizationalUnitRepository;
import com.unicconnect.repository.StaffPositionAssignmentRepository;
import com.unicconnect.repository.StaffRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class OrganizationalUnitService {

    private final OrganizationalUnitRepository unitRepository;
    private final StaffRepository staffRepository;
    private final StaffPositionAssignmentRepository assignmentRepository;
    private final MajorRepository majorRepository;
    private final CourseRepository courseRepository;

    public OrganizationalUnitService(OrganizationalUnitRepository unitRepository,
                                     StaffRepository staffRepository,
                                     StaffPositionAssignmentRepository assignmentRepository,
                                     MajorRepository majorRepository,
                                     CourseRepository courseRepository) {
        this.unitRepository = unitRepository;
        this.staffRepository = staffRepository;
        this.assignmentRepository = assignmentRepository;
        this.majorRepository = majorRepository;
        this.courseRepository = courseRepository;
    }

    public List<OrganizationalUnitResponse> getAll() {
        return unitRepository.findAll().stream().map(OrganizationalUnitService::toResponse).toList();
    }

    public OrganizationalUnitResponse getById(UUID unitId) {
        return toResponse(findUnit(unitId));
    }

    @Transactional
    public OrganizationalUnitResponse create(OrganizationalUnitRequest request) {
        if (unitRepository.existsByUnitCode(request.unitCode())) {
            throw new DuplicateResourceException("Unit code already exists: " + request.unitCode());
        }
        OrganizationalUnit unit = new OrganizationalUnit();
        unit.setUnitName(request.unitName());
        unit.setUnitCode(request.unitCode());
        unit.setUnitType(request.unitType());
        unit.setDescription(request.description());
        return toResponse(unitRepository.save(unit));
    }

    @Transactional
    public OrganizationalUnitResponse update(UUID unitId, OrganizationalUnitRequest request) {
        OrganizationalUnit unit = findUnit(unitId);
        if (!unit.getUnitCode().equals(request.unitCode()) && unitRepository.existsByUnitCode(request.unitCode())) {
            throw new DuplicateResourceException("Unit code already exists: " + request.unitCode());
        }
        unit.setUnitName(request.unitName());
        unit.setUnitCode(request.unitCode());
        unit.setUnitType(request.unitType());
        unit.setDescription(request.description());
        return toResponse(unitRepository.save(unit));
    }

    @Transactional
    public void delete(UUID unitId) {
        findUnit(unitId);
        unitRepository.deleteById(unitId);
    }

    public List<StaffResponse> getStaff(UUID unitId) {
        findUnit(unitId);
        List<com.unicconnect.entity.Staff> staff = staffRepository.findByUnit_UnitId(unitId);
        Map<UUID, List<com.unicconnect.entity.StaffPositionAssignment>> assignmentsByStaff = assignmentRepository
                .findAll().stream()
                .collect(Collectors.groupingBy(pa -> pa.getStaff().getStaffId()));
        return staff.stream()
                .map(s -> StaffService.toResponse(s,
                        assignmentsByStaff.getOrDefault(s.getStaffId(), java.util.Collections.emptyList())))
                .toList();
    }

    public List<MajorResponse> getMajors(UUID unitId) {
        findUnit(unitId);
        return majorRepository.findByUnit_UnitId(unitId).stream().map(MajorService::toResponse).toList();
    }

    public List<CourseResponse> getCourses(UUID unitId) {
        findUnit(unitId);
        return courseRepository.findByUnit_UnitId(unitId).stream().map(CourseService::toResponse).toList();
    }

    public OrganizationalUnit findUnit(UUID unitId) {
        return unitRepository.findById(unitId)
                .orElseThrow(() -> new ResourceNotFoundException("Organizational unit not found"));
    }

    static OrganizationalUnitResponse toResponse(OrganizationalUnit unit) {
        return new OrganizationalUnitResponse(
                unit.getUnitId(), unit.getUnitName(), unit.getUnitCode(),
                unit.getUnitType(), unit.getDescription());
    }
}