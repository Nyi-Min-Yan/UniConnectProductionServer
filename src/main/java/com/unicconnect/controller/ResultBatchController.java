package com.unicconnect.controller;

import com.unicconnect.dto.request.CreateResultBatchRequest;
import com.unicconnect.dto.response.ResultBatchResponse;
import com.unicconnect.dto.response.ResultUploadSummary;
import com.unicconnect.service.ResultBatchService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/result-batches")
public class ResultBatchController {

    private final ResultBatchService service;

    public ResultBatchController(ResultBatchService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<ResultBatchResponse>> getAll(
            @RequestParam(required = false) UUID termId,
            @RequestParam(required = false) UUID semesterId,
            @RequestParam(required = false) UUID examTypeId) {
        return ResponseEntity.ok(service.getAll(termId, semesterId, examTypeId));
    }

    @GetMapping("/{batchId}")
    public ResponseEntity<ResultBatchResponse> getById(@PathVariable UUID batchId) {
        return ResponseEntity.ok(service.getById(batchId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ResultUploadSummary> upload(
            @RequestParam UUID termId,
            @RequestParam UUID examTypeId,
            @RequestParam UUID semesterId,
            @RequestParam(required = false) UUID uploadedByStaffId,
            @RequestParam("files") MultipartFile[] files) {
        return ResponseEntity.ok(service.upload(termId, examTypeId, semesterId, uploadedByStaffId, files));
    }

    @PostMapping
    public ResponseEntity<ResultBatchResponse> create(
            @Valid @RequestBody CreateResultBatchRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PutMapping("/{batchId}")
    public ResponseEntity<ResultBatchResponse> update(
            @PathVariable UUID batchId,
            @Valid @RequestBody CreateResultBatchRequest request) {
        return ResponseEntity.ok(service.update(batchId, request));
    }

    @DeleteMapping("/{batchId}")
    public ResponseEntity<Void> delete(@PathVariable UUID batchId) {
        service.delete(batchId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{batchId}/publish")
    public ResponseEntity<ResultBatchResponse> publish(@PathVariable UUID batchId) {
        return ResponseEntity.ok(service.publish(batchId));
    }
}
