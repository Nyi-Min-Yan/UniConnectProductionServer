package com.unicconnect.controller;

import com.unicconnect.dto.request.PositionRequest;
import com.unicconnect.dto.response.PositionResponse;
import com.unicconnect.service.PositionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/positions")
public class PositionController {

    private final PositionService service;

    public PositionController(PositionService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<PositionResponse>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @GetMapping("/{positionId}")
    public ResponseEntity<PositionResponse> getById(@PathVariable UUID positionId) {
        return ResponseEntity.ok(service.getById(positionId));
    }

    @PostMapping
    public ResponseEntity<PositionResponse> create(@Valid @RequestBody PositionRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PutMapping("/{positionId}")
    public ResponseEntity<PositionResponse> update(@PathVariable UUID positionId,
                                                   @Valid @RequestBody PositionRequest request) {
        return ResponseEntity.ok(service.update(positionId, request));
    }

    @DeleteMapping("/{positionId}")
    public ResponseEntity<Void> delete(@PathVariable UUID positionId) {
        service.delete(positionId);
        return ResponseEntity.noContent().build();
    }
}
