package com.unicconnect.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record StaffResponse(
        UUID staffId,
        UUID userId,
        String staffNo,
        String staffName,
        String phoneNo,
        Integer batchYear,
        String address,
        UUID unitId,
        String unitName,
        LocalDate joinedAt,
        LocalDate leftDate,
        Instant createdAt,
        List<String> positions
) {}