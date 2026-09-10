package com.unicconnect.controller;

import com.unicconnect.dto.request.CreateStaffUserRequest;
import com.unicconnect.dto.request.StaffPositionAssignmentRequest;
import com.unicconnect.dto.request.StaffRequest;
import com.unicconnect.dto.response.ImportResultResponse;
import com.unicconnect.dto.response.LecturerResponse;
import com.unicconnect.dto.response.StaffPositionAssignmentResponse;
import com.unicconnect.dto.response.StaffResponse;
import com.unicconnect.dto.response.TeachingAssignmentResponse;
import com.unicconnect.service.ExcelImportService;
import com.unicconnect.service.StaffService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/staff")
public class StaffController {

    private final StaffService service;
    private final ExcelImportService importService;

    public StaffController(StaffService service, ExcelImportService importService) {
        this.service = service;
        this.importService = importService;
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResultResponse> importExcel(@RequestParam("file") MultipartFile file,
                                                            @RequestParam String type) {
        return ResponseEntity.ok(importService.importStaff(file, type));
    }

    @GetMapping
    public ResponseEntity<List<StaffResponse>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @GetMapping("/lecturers")
    public ResponseEntity<List<LecturerResponse>> getLecturers(
            @RequestParam(required = false) UUID termId) {
        return ResponseEntity.ok(service.getLecturers(termId));
    }

    @GetMapping("/me")
    public ResponseEntity<StaffResponse> getCurrentStaff() {
        return ResponseEntity.ok(service.getCurrentStaff());
    }

    @GetMapping("/{staffId}")
    public ResponseEntity<StaffResponse> getById(@PathVariable UUID staffId) {
        return ResponseEntity.ok(service.getById(staffId));
    }

    @PostMapping
    public ResponseEntity<StaffResponse> create(@Valid @RequestBody StaffRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PostMapping("/register")
    public ResponseEntity<StaffResponse> register(@Valid @RequestBody CreateStaffUserRequest request) {
        return ResponseEntity.ok(service.createWithUser(request));
    }

    @PutMapping("/{staffId}")
    public ResponseEntity<StaffResponse> update(@PathVariable UUID staffId,
                                                @Valid @RequestBody StaffRequest request) {
        return ResponseEntity.ok(service.update(staffId, request));
    }

    @DeleteMapping("/{staffId}")
    public ResponseEntity<Void> delete(@PathVariable UUID staffId) {
        service.delete(staffId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{staffId}/position-assignments")
    public ResponseEntity<List<StaffPositionAssignmentResponse>> getPositionAssignments(
            @PathVariable UUID staffId) {
        return ResponseEntity.ok(service.getPositionAssignments(staffId));
    }

    @PostMapping("/{staffId}/position-assignments")
    public ResponseEntity<StaffPositionAssignmentResponse> assignPosition(
            @PathVariable UUID staffId,
            @Valid @RequestBody StaffPositionAssignmentRequest request) {
        return ResponseEntity.ok(service.assignPosition(staffId, request));
    }

    @PutMapping("/{staffId}/position-assignments/{assignmentId}")
    public ResponseEntity<StaffPositionAssignmentResponse> updatePositionAssignment(
            @PathVariable UUID staffId,
            @PathVariable UUID assignmentId,
            @Valid @RequestBody StaffPositionAssignmentRequest request) {
        return ResponseEntity.ok(service.updatePositionAssignment(staffId, assignmentId, request));
    }

    @DeleteMapping("/{staffId}/position-assignments/{assignmentId}")
    public ResponseEntity<Void> removePositionAssignment(@PathVariable UUID staffId,
                                                         @PathVariable UUID assignmentId) {
        service.removePositionAssignment(staffId, assignmentId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{staffId}/teaching-assignments")
    public ResponseEntity<List<TeachingAssignmentResponse>> getTeachingAssignments(
            @PathVariable UUID staffId) {
        return ResponseEntity.ok(service.getTeachingAssignments(staffId));
    }
}
