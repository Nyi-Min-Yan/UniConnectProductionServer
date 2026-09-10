package com.unicconnect.controller;

import com.unicconnect.dto.request.ScheduleRequest;
import com.unicconnect.dto.response.ClassSessionResponse;
import com.unicconnect.dto.response.ScheduleResponse;
import com.unicconnect.service.ClassScheduleService;
import com.unicconnect.service.ClassSessionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/schedules")
public class ClassScheduleController {

    private final ClassScheduleService scheduleService;
    private final ClassSessionService sessionService;

    public ClassScheduleController(ClassScheduleService scheduleService,
                                   ClassSessionService sessionService) {
        this.scheduleService = scheduleService;
        this.sessionService = sessionService;
    }

    @GetMapping
    public ResponseEntity<List<ScheduleResponse>> getAll(
            @RequestParam(required = false) UUID termId,
            @RequestParam(required = false) UUID sectionId,
            @RequestParam(required = false) UUID staffId,
            @RequestParam(required = false) Integer dayOfWeek) {
        return ResponseEntity.ok(scheduleService.getAll(termId, sectionId, staffId, dayOfWeek));
    }

    /**
     * Normal timetable view: only the schedules of the term's published generation.
     */
    @GetMapping("/published")
    public ResponseEntity<List<ScheduleResponse>> getPublished(@RequestParam UUID termId) {
        return ResponseEntity.ok(scheduleService.getPublished(termId));
    }

    @GetMapping("/{scheduleId}")
    public ResponseEntity<ScheduleResponse> getById(@PathVariable UUID scheduleId) {
        return ResponseEntity.ok(scheduleService.getById(scheduleId));
    }

    @PostMapping
    public ResponseEntity<ScheduleResponse> create(@Valid @RequestBody ScheduleRequest request) {
        return ResponseEntity.ok(scheduleService.create(request));
    }

    @PutMapping("/{scheduleId}")
    public ResponseEntity<ScheduleResponse> update(@PathVariable UUID scheduleId,
                                                   @Valid @RequestBody ScheduleRequest request) {
        return ResponseEntity.ok(scheduleService.update(scheduleId, request));
    }

    @DeleteMapping("/{scheduleId}")
    public ResponseEntity<Void> delete(@PathVariable UUID scheduleId) {
        scheduleService.delete(scheduleId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{scheduleId}/sessions")
    public ResponseEntity<List<ClassSessionResponse>> getSessions(@PathVariable UUID scheduleId) {
        return ResponseEntity.ok(sessionService.getAll(scheduleId));
    }
}
