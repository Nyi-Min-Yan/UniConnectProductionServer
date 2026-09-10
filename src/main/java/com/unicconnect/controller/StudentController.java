package com.unicconnect.controller;

import com.unicconnect.dto.request.CreateStudentUserRequest;
import com.unicconnect.dto.request.StudentRequest;
import com.unicconnect.dto.response.AttendanceResponse;
import com.unicconnect.dto.response.ImportResultResponse;
import com.unicconnect.dto.response.ResultDocumentResponse;
import com.unicconnect.dto.response.ScheduleResponse;
import com.unicconnect.dto.response.StudentResponse;
import com.unicconnect.dto.response.StudentRollCallResponse;
import com.unicconnect.service.ExcelImportService;
import com.unicconnect.service.StudentRollCallService;
import com.unicconnect.service.StudentService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/students")
public class StudentController {

    private final StudentService service;
    private final ExcelImportService importService;
    private final StudentRollCallService rollCallService;

    public StudentController(StudentService service, ExcelImportService importService,
                             StudentRollCallService rollCallService) {
        this.service = service;
        this.importService = importService;
        this.rollCallService = rollCallService;
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResultResponse> importExcel(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(importService.importStudents(file));
    }

    @GetMapping
    public ResponseEntity<List<StudentResponse>> getAll(
            @RequestParam(required = false) UUID majorId,
            @RequestParam(required = false) UUID semesterId,
            @RequestParam(required = false) UUID sectionId,
            @RequestParam(required = false) UUID termId) {
        return ResponseEntity.ok(service.getAll(majorId, semesterId, sectionId, termId));
    }

    @GetMapping("/{studentId}")
    public ResponseEntity<StudentResponse> getById(@PathVariable UUID studentId) {
        return ResponseEntity.ok(service.getById(studentId));
    }

    @PostMapping
    public ResponseEntity<StudentResponse> create(@Valid @RequestBody StudentRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PostMapping("/register")
    public ResponseEntity<StudentResponse> register(@Valid @RequestBody CreateStudentUserRequest request) {
        return ResponseEntity.ok(service.createWithUser(request));
    }

    @PutMapping("/{studentId}")
    public ResponseEntity<StudentResponse> update(@PathVariable UUID studentId,
                                                  @Valid @RequestBody StudentRequest request) {
        return ResponseEntity.ok(service.update(studentId, request));
    }

    @DeleteMapping("/{studentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID studentId) {
        service.delete(studentId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{studentId}/attendance")
    public ResponseEntity<List<AttendanceResponse>> getAttendance(@PathVariable UUID studentId) {
        return ResponseEntity.ok(service.getAttendance(studentId));
    }

    @GetMapping("/{studentId}/rollcall")
    public ResponseEntity<StudentRollCallResponse> getRollCall(@PathVariable UUID studentId) {
        return ResponseEntity.ok(rollCallService.summary(studentId));
    }

    @GetMapping("/{studentId}/schedules")
    public ResponseEntity<List<ScheduleResponse>> getSchedules(@PathVariable UUID studentId,
                                                               @RequestParam(required = false) UUID termId) {
        return ResponseEntity.ok(service.getSchedules(studentId, termId));
    }

    @GetMapping("/{studentId}/results")
    public ResponseEntity<List<ResultDocumentResponse>> getResults(@PathVariable UUID studentId) {
        return ResponseEntity.ok(service.getResults(studentId));
    }

    @GetMapping("/{studentId}/results/{documentId}")
    public ResponseEntity<ResultDocumentResponse> getResult(@PathVariable UUID studentId,
                                                            @PathVariable UUID documentId) {
        return ResponseEntity.ok(service.getResult(studentId, documentId));
    }
}
