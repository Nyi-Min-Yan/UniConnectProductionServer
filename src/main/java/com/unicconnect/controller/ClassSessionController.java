package com.unicconnect.controller;

import com.unicconnect.dto.request.ClassSessionRequest;
import com.unicconnect.dto.response.ClassSessionResponse;
import com.unicconnect.service.ClassSessionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/sessions")
public class ClassSessionController {

    private final ClassSessionService service;

    public ClassSessionController(ClassSessionService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<ClassSessionResponse>> getAll(
            @RequestParam(required = false) UUID scheduleId) {
        return ResponseEntity.ok(service.getAll(scheduleId));
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<ClassSessionResponse> getById(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(service.getById(sessionId));
    }

    @PostMapping
    public ResponseEntity<ClassSessionResponse> create(@Valid @RequestBody ClassSessionRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PutMapping("/{sessionId}")
    public ResponseEntity<ClassSessionResponse> update(@PathVariable UUID sessionId,
                                                       @Valid @RequestBody ClassSessionRequest request) {
        return ResponseEntity.ok(service.update(sessionId, request));
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> delete(@PathVariable UUID sessionId) {
        service.delete(sessionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{sessionId}/start")
    public ResponseEntity<ClassSessionResponse> start(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(service.startSession(sessionId));
    }

    @PostMapping("/{sessionId}/end")
    public ResponseEntity<ClassSessionResponse> end(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(service.endSession(sessionId));
    }
}
