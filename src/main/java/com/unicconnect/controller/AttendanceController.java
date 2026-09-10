package com.unicconnect.controller;

import com.unicconnect.dto.request.MarkAttendanceRequest;
import com.unicconnect.dto.request.UpdateAttendanceRequest;
import com.unicconnect.dto.response.AttendanceResponse;
import com.unicconnect.service.AttendanceService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/attendance")
public class AttendanceController {

    private final AttendanceService service;

    public AttendanceController(AttendanceService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<AttendanceResponse>> getAll(
            @RequestParam UUID sessionId) {
        return ResponseEntity.ok(service.getAll(sessionId));
    }

    @GetMapping("/{attendanceId}")
    public ResponseEntity<AttendanceResponse> getById(@PathVariable UUID attendanceId) {
        return ResponseEntity.ok(service.getById(attendanceId));
    }

    @PostMapping("/{sessionId}/mark")
    public ResponseEntity<List<AttendanceResponse>> markAttendance(
            @PathVariable UUID sessionId,
            @Valid @RequestBody MarkAttendanceRequest request) {
        return ResponseEntity.ok(service.markAttendance(sessionId, request));
    }

    @PutMapping("/{attendanceId}")
    public ResponseEntity<AttendanceResponse> update(@PathVariable UUID attendanceId,
                                                     @Valid @RequestBody UpdateAttendanceRequest request) {
        return ResponseEntity.ok(service.update(attendanceId, request));
    }

    @DeleteMapping("/{attendanceId}")
    public ResponseEntity<Void> delete(@PathVariable UUID attendanceId) {
        service.delete(attendanceId);
        return ResponseEntity.noContent().build();
    }
}
