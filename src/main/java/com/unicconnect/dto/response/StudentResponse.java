package com.unicconnect.dto.response;

import java.time.Instant;
import java.util.UUID;

public record StudentResponse(
        UUID studentId,
        UUID userId,
        String email,
        UUID majorId,
        String majorCode,
        UUID semesterId,
        Integer semesterNo,
        UUID sectionId,
        String sectionName,
        UUID termId,
        String academicYear,
        String rollNo,
        String studentName,
        String phoneNo,
        String address,
        Integer batchYear,
        Instant createdAt
) {}