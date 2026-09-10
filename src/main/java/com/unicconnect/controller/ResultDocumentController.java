package com.unicconnect.controller;

import com.unicconnect.dto.response.ResultDocumentResponse;
import com.unicconnect.service.ResultDocumentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/result-documents")
public class ResultDocumentController {

    private final ResultDocumentService service;

    public ResultDocumentController(ResultDocumentService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<ResultDocumentResponse>> getAll(
            @RequestParam UUID batchId) {
        return ResponseEntity.ok(service.getAll(batchId));
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<ResultDocumentResponse> getById(@PathVariable UUID documentId) {
        return ResponseEntity.ok(service.getById(documentId));
    }

    @GetMapping("/by-student/{studentId}")
    public ResponseEntity<List<ResultDocumentResponse>> getByStudent(@PathVariable UUID studentId) {
        return ResponseEntity.ok(service.getByStudent(studentId));
    }

    @GetMapping("/by-student-and-batch")
    public ResponseEntity<ResultDocumentResponse> getByStudentAndBatch(
            @RequestParam UUID batchId,
            @RequestParam UUID studentId) {
        return ResponseEntity.ok(service.getByStudentAndBatch(batchId, studentId));
    }

    @PostMapping("/{documentId}/release")
    public ResponseEntity<ResultDocumentResponse> release(@PathVariable UUID documentId) {
        return ResponseEntity.ok(service.release(documentId));
    }

    @PostMapping("/{documentId}/block")
    public ResponseEntity<ResultDocumentResponse> block(@PathVariable UUID documentId,
                                                        @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(service.block(documentId, reason));
    }

    @PostMapping("/{documentId}/view")
    public ResponseEntity<ResultDocumentResponse> recordView(@PathVariable UUID documentId) {
        return ResponseEntity.ok(service.recordView(documentId));
    }

    @PostMapping("/{documentId}/download")
    public ResponseEntity<ResultDocumentResponse> recordDownload(@PathVariable UUID documentId) {
        return ResponseEntity.ok(service.recordDownload(documentId));
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID documentId) {
        service.delete(documentId);
        return ResponseEntity.noContent().build();
    }
}
