package com.unicconnect.controller;

import com.unicconnect.dto.request.AddTeachingGroupMembersRequest;
import com.unicconnect.dto.request.CreateTeachingGroupRequest;
import com.unicconnect.dto.response.TeachingGroupResponse;
import com.unicconnect.service.TeachingAssignmentGroupService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/teaching-groups")
public class TeachingAssignmentGroupController {

    private final TeachingAssignmentGroupService service;

    public TeachingAssignmentGroupController(TeachingAssignmentGroupService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<TeachingGroupResponse>> getAll(
            @RequestParam(required = false) UUID termId) {
        return ResponseEntity.ok(service.getAll(termId));
    }

    @GetMapping("/{groupId}")
    public ResponseEntity<TeachingGroupResponse> getById(@PathVariable UUID groupId) {
        return ResponseEntity.ok(service.getById(groupId));
    }

    @PostMapping
    public ResponseEntity<TeachingGroupResponse> create(
            @Valid @RequestBody CreateTeachingGroupRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PostMapping("/{groupId}/members")
    public ResponseEntity<TeachingGroupResponse> addMembers(
            @PathVariable UUID groupId,
            @Valid @RequestBody AddTeachingGroupMembersRequest request) {
        return ResponseEntity.ok(service.addMembers(groupId, request.assignmentIds()));
    }

    @DeleteMapping("/{groupId}")
    public ResponseEntity<Void> delete(@PathVariable UUID groupId) {
        service.delete(groupId);
        return ResponseEntity.noContent().build();
    }
}
