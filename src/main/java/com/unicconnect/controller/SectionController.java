package com.unicconnect.controller;

import com.unicconnect.dto.request.SectionRequest;
import com.unicconnect.dto.response.SectionResponse;
import com.unicconnect.service.SectionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/sections")
public class SectionController {

    private final SectionService service;

    public SectionController(SectionService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<SectionResponse>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @GetMapping("/{sectionId}")
    public ResponseEntity<SectionResponse> getById(@PathVariable UUID sectionId) {
        return ResponseEntity.ok(service.getById(sectionId));
    }

    @PostMapping
    public ResponseEntity<SectionResponse> create(@Valid @RequestBody SectionRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PutMapping("/{sectionId}")
    public ResponseEntity<SectionResponse> update(@PathVariable UUID sectionId,
                                                  @Valid @RequestBody SectionRequest request) {
        return ResponseEntity.ok(service.update(sectionId, request));
    }

    @DeleteMapping("/{sectionId}")
    public ResponseEntity<Void> delete(@PathVariable UUID sectionId) {
        service.delete(sectionId);
        return ResponseEntity.noContent().build();
    }
}
