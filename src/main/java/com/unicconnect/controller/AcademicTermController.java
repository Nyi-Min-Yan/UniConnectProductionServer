package com.unicconnect.controller;

import com.unicconnect.dto.request.AcademicTermRequest;
import com.unicconnect.dto.response.*;
import com.unicconnect.service.AcademicTermService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/terms")
public class AcademicTermController {

    private final AcademicTermService service;

    public AcademicTermController(AcademicTermService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<AcademicTermResponse>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @GetMapping("/{termId}")
    public ResponseEntity<AcademicTermResponse> getById(@PathVariable UUID termId) {
        return ResponseEntity.ok(service.getById(termId));
    }

    @PostMapping
    public ResponseEntity<AcademicTermResponse> create(@Valid @RequestBody AcademicTermRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PutMapping("/{termId}")
    public ResponseEntity<AcademicTermResponse> update(@PathVariable UUID termId,
                                                       @Valid @RequestBody AcademicTermRequest request) {
        return ResponseEntity.ok(service.update(termId, request));
    }

    @DeleteMapping("/{termId}")
    public ResponseEntity<Void> delete(@PathVariable UUID termId) {
        service.delete(termId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{termId}/students")
    public ResponseEntity<List<StudentResponse>> getStudents(@PathVariable UUID termId) {
        return ResponseEntity.ok(service.getStudents(termId));
    }

    @GetMapping("/{termId}/teaching-assignments")
    public ResponseEntity<List<TeachingAssignmentResponse>> getTeachingAssignments(
            @PathVariable UUID termId) {
        return ResponseEntity.ok(service.getTeachingAssignments(termId));
    }

    @GetMapping("/{termId}/schedules")
    public ResponseEntity<List<ScheduleResponse>> getSchedules(@PathVariable UUID termId) {
        return ResponseEntity.ok(service.getSchedules(termId));
    }

    @GetMapping("/{termId}/results")
    public ResponseEntity<List<ResultBatchResponse>> getResults(@PathVariable UUID termId) {
        return ResponseEntity.ok(service.getResults(termId));
    }
}
