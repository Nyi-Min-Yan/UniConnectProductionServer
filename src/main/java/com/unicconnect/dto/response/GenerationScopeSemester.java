package com.unicconnect.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Semesters applicable to a generation (from the selected exam type) together
 * with the existing sections that actually have teaching assignments for the
 * term and semester. Never fabricated — driven by real data.
 */
public record GenerationScopeSemester(
        UUID semesterId,
        Integer semesterNo,
        List<SectionInfo> sections
) {
    public record SectionInfo(
            UUID sectionId,
            String sectionName
    ) {}
}
