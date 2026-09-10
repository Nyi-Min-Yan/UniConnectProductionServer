package com.unicconnect.controller;

import com.unicconnect.dto.request.OrganizationalUnitRequest;
import com.unicconnect.dto.response.CourseResponse;
import com.unicconnect.dto.response.MajorResponse;
import com.unicconnect.dto.response.OrganizationalUnitResponse;
import com.unicconnect.dto.response.StaffResponse;
import com.unicconnect.service.OrganizationalUnitService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/organizational-units")
public class OrganizationalUnitController {

    private final OrganizationalUnitService service;

    public OrganizationalUnitController(OrganizationalUnitService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<OrganizationalUnitResponse>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @GetMapping("/{unitId}")
    public ResponseEntity<OrganizationalUnitResponse> getById(@PathVariable UUID unitId) {
        return ResponseEntity.ok(service.getById(unitId));
    }

    @PostMapping
    public ResponseEntity<OrganizationalUnitResponse> create(@Valid @RequestBody OrganizationalUnitRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PutMapping("/{unitId}")
    public ResponseEntity<OrganizationalUnitResponse> update(@PathVariable UUID unitId,
                                                             @Valid @RequestBody OrganizationalUnitRequest request) {
        return ResponseEntity.ok(service.update(unitId, request));
    }

    @DeleteMapping("/{unitId}")
    public ResponseEntity<Void> delete(@PathVariable UUID unitId) {
        service.delete(unitId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{unitId}/staff")
    public ResponseEntity<List<StaffResponse>> getStaff(@PathVariable UUID unitId) {
        return ResponseEntity.ok(service.getStaff(unitId));
    }

    @GetMapping("/{unitId}/majors")
    public ResponseEntity<List<MajorResponse>> getMajors(@PathVariable UUID unitId) {
        return ResponseEntity.ok(service.getMajors(unitId));
    }

    @GetMapping("/{unitId}/courses")
    public ResponseEntity<List<CourseResponse>> getCourses(@PathVariable UUID unitId) {
        return ResponseEntity.ok(service.getCourses(unitId));
    }
}
