package com.unicconnect.controller;

import com.unicconnect.dto.request.TimeSlotRequest;
import com.unicconnect.dto.response.TimeSlotResponse;
import com.unicconnect.service.TimeSlotService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/time-slots")
public class TimeSlotController {

    private final TimeSlotService service;

    public TimeSlotController(TimeSlotService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<TimeSlotResponse>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @GetMapping("/{slotId}")
    public ResponseEntity<TimeSlotResponse> getById(@PathVariable UUID slotId) {
        return ResponseEntity.ok(service.getById(slotId));
    }

    @PostMapping
    public ResponseEntity<TimeSlotResponse> create(@Valid @RequestBody TimeSlotRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PutMapping("/{slotId}")
    public ResponseEntity<TimeSlotResponse> update(@PathVariable UUID slotId,
                                                   @Valid @RequestBody TimeSlotRequest request) {
        return ResponseEntity.ok(service.update(slotId, request));
    }

    @DeleteMapping("/{slotId}")
    public ResponseEntity<Void> delete(@PathVariable UUID slotId) {
        service.delete(slotId);
        return ResponseEntity.noContent().build();
    }
}
