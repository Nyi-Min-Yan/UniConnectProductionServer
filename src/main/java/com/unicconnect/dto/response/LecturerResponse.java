package com.unicconnect.dto.response;

import java.util.List;
import java.util.UUID;

public record LecturerResponse(
        UUID staffId,
        String staffNo,
        String staffName,
        String email,
        String phoneNo,
        UUID unitId,
        String unitName,
        List<String> positions,
        int courseCount,
        List<AssignedCourseResponse> assignedCourses
) {}