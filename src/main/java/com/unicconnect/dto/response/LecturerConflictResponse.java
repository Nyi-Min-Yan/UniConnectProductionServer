package com.unicconnect.dto.response;

import java.util.UUID;

/**
 * Structured LECTURER_CONFLICT rejection for a manual MOVE/SWAP. Carries enough
 * detail for the UI to render e.g.
 * "Lecturer A already teaches CS-xxxx in Semester 6 Section B at Monday P5."
 */
public record LecturerConflictResponse(
        boolean valid,
        String reason,
        String message,
        UUID lecturerId,
        String lecturerName,
        String day,
        String period,
        Integer conflictingSemester,
        String conflictingSection,
        String conflictingCourseCode
) {}