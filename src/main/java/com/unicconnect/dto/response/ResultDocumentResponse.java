package com.unicconnect.dto.response;

import com.unicconnect.entity.ReleaseStatus;

import java.time.Instant;
import java.util.UUID;

public record ResultDocumentResponse(
        UUID resultDocumentId,
        UUID batchId,
        String examTypeName,
        UUID studentId,
        String rollNo,
        String studentName,
        String pdfFileName,
        String storageObjectPath,
        ReleaseStatus releaseStatus,
        String blockedReason,
        Instant viewedAt,
        Instant downloadedAt,
        Instant createdAt
) {}