package com.unicconnect.dto.response;

import com.unicconnect.entity.SessionStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ClassSessionResponse(
        UUID sessionId,
        UUID scheduleId,
        UUID termId,
        String courseCode,
        UUID sectionId,
        String sectionName,
        LocalDate sessionDate,
        SessionStatus sessionStatus,
        Instant startedAt,
        Instant endedAt
) {}