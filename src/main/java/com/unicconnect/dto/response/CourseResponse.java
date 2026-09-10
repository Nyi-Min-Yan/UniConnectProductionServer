package com.unicconnect.dto.response;

import java.util.UUID;

public record CourseResponse(
        UUID courseId,
        UUID unitId,
        String unitCode,
        String courseCode,
        String courseName,
        Integer creditUnit,
        UUID majorId,
        String majorCode,
        UUID semesterId,
        Integer semesterNo,
        boolean isRequired,
        int displayOrder
) {}