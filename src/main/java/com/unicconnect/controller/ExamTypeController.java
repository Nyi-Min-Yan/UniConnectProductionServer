package com.unicconnect.controller;

import com.unicconnect.dto.request.ExamTypeRequest;
import com.unicconnect.dto.response.ExamTypeResponse;
import com.unicconnect.service.ExamTypeService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/exam-types")
public class ExamTypeController {

    private final ExamTypeService service;

    public ExamTypeController(ExamTypeService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<ExamTypeResponse>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @GetMapping("/{examTypeId}")
    public ResponseEntity<ExamTypeResponse> getById(@PathVariable UUID examTypeId) {
        return ResponseEntity.ok(service.getById(examTypeId));
    }

    @PostMapping
    public ResponseEntity<ExamTypeResponse> create(@Valid @RequestBody ExamTypeRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PutMapping("/{examTypeId}")
    public ResponseEntity<ExamTypeResponse> update(@PathVariable UUID examTypeId,
                                                   @Valid @RequestBody ExamTypeRequest request) {
        return ResponseEntity.ok(service.update(examTypeId, request));
    }

    @DeleteMapping("/{examTypeId}")
    public ResponseEntity<Void> delete(@PathVariable UUID examTypeId) {
        service.delete(examTypeId);
        return ResponseEntity.noContent().build();
    }
}
