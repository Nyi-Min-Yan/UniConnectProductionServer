package com.unicconnect.controller;

import com.unicconnect.dto.request.TeachingAssignmentRequest;
import com.unicconnect.dto.response.TeachingAssignmentResponse;
import com.unicconnect.service.TeachingAssignmentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/teaching-assignments")
public class TeachingAssignmentController {

    private final TeachingAssignmentService service;

    public TeachingAssignmentController(TeachingAssignmentService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<TeachingAssignmentResponse>> getAll(
            @RequestParam(required = false) UUID termId,
            @RequestParam(required = false) UUID staffId,
            @RequestParam(required = false) UUID courseId,
            @RequestParam(required = false) UUID sectionId) {
        return ResponseEntity.ok(service.getAll(termId, staffId, courseId, sectionId));
    }

    @GetMapping("/{assignmentId}")
    public ResponseEntity<TeachingAssignmentResponse> getById(@PathVariable UUID assignmentId) {
        return ResponseEntity.ok(service.getById(assignmentId));
    }

    @PostMapping
    public ResponseEntity<TeachingAssignmentResponse> create(
            @Valid @RequestBody TeachingAssignmentRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PutMapping("/{assignmentId}")
    public ResponseEntity<TeachingAssignmentResponse> update(
            @PathVariable UUID assignmentId,
            @Valid @RequestBody TeachingAssignmentRequest request) {
        return ResponseEntity.ok(service.update(assignmentId, request));
    }

    @DeleteMapping("/{assignmentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID assignmentId) {
        service.delete(assignmentId);
        return ResponseEntity.noContent().build();
    }
}
