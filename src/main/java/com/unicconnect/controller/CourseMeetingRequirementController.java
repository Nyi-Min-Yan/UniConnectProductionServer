package com.unicconnect.controller;

import com.unicconnect.dto.request.MeetingRequirementRequest;
import com.unicconnect.dto.response.MeetingRequirementResponse;
import com.unicconnect.service.CourseMeetingRequirementService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/meeting-requirements")
public class CourseMeetingRequirementController {

    private final CourseMeetingRequirementService service;

    public CourseMeetingRequirementController(CourseMeetingRequirementService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<MeetingRequirementResponse>> getAll(
            @RequestParam(required = false) UUID unitId,
            @RequestParam(required = false) UUID semesterId) {
        return ResponseEntity.ok(service.getAll(unitId, semesterId));
    }

    @GetMapping("/{requirementId}")
    public ResponseEntity<MeetingRequirementResponse> getById(@PathVariable UUID requirementId) {
        return ResponseEntity.ok(service.getById(requirementId));
    }

    @PostMapping
    public ResponseEntity<MeetingRequirementResponse> create(
            @Valid @RequestBody MeetingRequirementRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PutMapping("/{requirementId}")
    public ResponseEntity<MeetingRequirementResponse> update(
            @PathVariable UUID requirementId,
            @Valid @RequestBody MeetingRequirementRequest request) {
        return ResponseEntity.ok(service.update(requirementId, request));
    }

    @DeleteMapping("/{requirementId}")
    public ResponseEntity<Void> delete(@PathVariable UUID requirementId) {
        service.delete(requirementId);
        return ResponseEntity.noContent().build();
    }
}
