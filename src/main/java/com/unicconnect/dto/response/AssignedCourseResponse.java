package com.unicconnect.dto.response;

import java.util.List;
import java.util.UUID;

public record AssignedCourseResponse(
        UUID courseId,
        String courseCode,
        String courseName,
        UUID semesterId,
        Integer semesterNo,
        List<SectionInfoResponse> sections
) {}