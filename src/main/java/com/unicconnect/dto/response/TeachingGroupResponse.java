package com.unicconnect.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TeachingGroupResponse(
        UUID groupId,
        UUID termId,
        String academicYear,
        UUID courseId,
        String courseCode,
        String courseName,
        Integer semesterNo,
        String groupName,
        Instant createdAt,
        List<Member> members
) {
    public record Member(
            UUID assignmentId,
            UUID staffId,
            String staffNo,
            String staffName,
            UUID sectionId,
            String sectionName,
            UUID unitId,
            String unitName
    ) {}
}
