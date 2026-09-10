package com.unicconnect.dto.request;

import com.unicconnect.entity.TermStatus;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record AcademicTermRequest(
        @NotNull String academicYear,
        LocalDate startDate,
        LocalDate endDate,
        TermStatus status
) {}