package com.unicconnect.dto.response;

import com.unicconnect.entity.GenerationStatus;

import java.time.Instant;
import java.util.UUID;

public record GenerationSessionResponse(
        UUID generationId,
        UUID termId,
        String academicYear,
        UUID generatedByStaffId,
        String generatedByStaffNo,
        GenerationStatus status,
        Instant startedAt,
        Instant publishedAt,
        Instant finishedAt,
        Instant createdAt,
        String failureReport
) {}