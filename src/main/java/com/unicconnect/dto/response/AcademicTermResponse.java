package com.unicconnect.dto.response;

import com.unicconnect.entity.TermStatus;

import java.time.LocalDate;
import java.util.UUID;

public record AcademicTermResponse(
        UUID termId,
        String academicYear,
        LocalDate startDate,
        LocalDate endDate,
        TermStatus status
) {}