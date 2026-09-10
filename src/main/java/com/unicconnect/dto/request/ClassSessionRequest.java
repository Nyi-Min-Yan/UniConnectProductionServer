package com.unicconnect.dto.request;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;

public record ClassSessionRequest(
        @NotNull UUID scheduleId,
        @NotNull LocalDate sessionDate,
        Instant startedAt,
        Instant endedAt,
        com.unicconnect.entity.SessionStatus sessionStatus
) {}