package com.unicconnect.controller;

import com.unicconnect.dto.request.CourseRequest;
import com.unicconnect.dto.request.MeetingRequirementRequest;
import com.unicconnect.dto.response.CourseResponse;
import com.unicconnect.dto.response.MeetingRequirementResponse;
import com.unicconnect.service.CourseService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/courses")
public class CourseController {

    private final CourseService service;

    public CourseController(CourseService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<CourseResponse>> getAll(
            @RequestParam(required = false) UUID majorId,
            @RequestParam(required = false) UUID semesterId,
            @RequestParam(required = false) UUID unitId) {
        return ResponseEntity.ok(service.getAll(majorId, semesterId, unitId));
    }

    @GetMapping("/{courseId}")
    public ResponseEntity<CourseResponse> getById(@PathVariable UUID courseId) {
        return ResponseEntity.ok(service.getById(courseId));
    }

    @PostMapping
    public ResponseEntity<CourseResponse> create(@Valid @RequestBody CourseRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PutMapping("/{courseId}")
    public ResponseEntity<CourseResponse> update(@PathVariable UUID courseId,
                                                 @Valid @RequestBody CourseRequest request) {
        return ResponseEntity.ok(service.update(courseId, request));
    }

    @DeleteMapping("/{courseId}")
    public ResponseEntity<Void> delete(@PathVariable UUID courseId) {
        service.delete(courseId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{courseId}/meeting-requirements")
    public ResponseEntity<List<MeetingRequirementResponse>> getMeetingRequirements(
            @PathVariable UUID courseId) {
        return ResponseEntity.ok(service.getMeetingRequirements(courseId));
    }

    @PostMapping("/{courseId}/meeting-requirements")
    public ResponseEntity<MeetingRequirementResponse> addMeetingRequirement(
            @PathVariable UUID courseId,
            @Valid @RequestBody MeetingRequirementRequest request) {
        return ResponseEntity.ok(service.addMeetingRequirement(courseId, request));
    }

    @PutMapping("/{courseId}/meeting-requirements/{requirementId}")
    public ResponseEntity<MeetingRequirementResponse> updateMeetingRequirement(
            @PathVariable UUID courseId,
            @PathVariable UUID requirementId,
            @Valid @RequestBody MeetingRequirementRequest request) {
        return ResponseEntity.ok(service.updateMeetingRequirement(courseId, requirementId, request));
    }

    @DeleteMapping("/{courseId}/meeting-requirements/{requirementId}")
    public ResponseEntity<Void> deleteMeetingRequirement(@PathVariable UUID courseId,
                                                         @PathVariable UUID requirementId) {
        service.deleteMeetingRequirement(courseId, requirementId);
        return ResponseEntity.noContent().build();
    }
}
