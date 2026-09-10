package com.unicconnect.controller;

import com.unicconnect.dto.request.MajorRequest;
import com.unicconnect.dto.response.CourseResponse;
import com.unicconnect.dto.response.MajorResponse;
import com.unicconnect.dto.response.StudentResponse;
import com.unicconnect.service.MajorService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/majors")
public class MajorController {

    private final MajorService service;

    public MajorController(MajorService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<MajorResponse>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @GetMapping("/{majorId}")
    public ResponseEntity<MajorResponse> getById(@PathVariable UUID majorId) {
        return ResponseEntity.ok(service.getById(majorId));
    }

    @PostMapping
    public ResponseEntity<MajorResponse> create(@Valid @RequestBody MajorRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PutMapping("/{majorId}")
    public ResponseEntity<MajorResponse> update(@PathVariable UUID majorId,
                                                @Valid @RequestBody MajorRequest request) {
        return ResponseEntity.ok(service.update(majorId, request));
    }

    @DeleteMapping("/{majorId}")
    public ResponseEntity<Void> delete(@PathVariable UUID majorId) {
        service.delete(majorId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{majorId}/courses")
    public ResponseEntity<List<CourseResponse>> getCourses(@PathVariable UUID majorId) {
        return ResponseEntity.ok(service.getCourses(majorId));
    }

    @GetMapping("/{majorId}/students")
    public ResponseEntity<List<StudentResponse>> getStudents(@PathVariable UUID majorId) {
        return ResponseEntity.ok(service.getStudents(majorId));
    }
}
