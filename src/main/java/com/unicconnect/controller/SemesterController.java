package com.unicconnect.controller;

import com.unicconnect.dto.response.SemesterResponse;
import com.unicconnect.service.SemesterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/semesters")
public class SemesterController {

    private final SemesterService service;

    public SemesterController(SemesterService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<SemesterResponse>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @GetMapping("/{semesterId}")
    public ResponseEntity<SemesterResponse> getById(@PathVariable UUID semesterId) {
        return ResponseEntity.ok(service.getById(semesterId));
    }
}
