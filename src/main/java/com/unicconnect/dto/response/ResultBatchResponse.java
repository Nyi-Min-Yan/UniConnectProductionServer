package com.unicconnect.dto.response;

import com.unicconnect.entity.BatchStatus;

import java.time.Instant;
import java.util.UUID;

public record ResultBatchResponse(
        UUID batchId,
        UUID termId,
        String academicYear,
        UUID examTypeId,
        String examTypeName,
        UUID semesterId,
        Integer semesterNo,
        UUID uploadedByStaffId,
        String uploadedByStaffNo,
        String uploadedType,
        String sourceFileName,
        int totalFiles,
        int matchedFiles,
        int failedFiles,
        BatchStatus status,
        Instant uploadedAt,
        Instant publishedAt
) {}