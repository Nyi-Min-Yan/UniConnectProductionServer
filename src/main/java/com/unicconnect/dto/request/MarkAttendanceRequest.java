package com.unicconnect.dto.request;

import com.unicconnect.entity.AttendanceStatus;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record MarkAttendanceRequest(
        List<AttendanceEntry> entries
) {

    /**
     * PRESENT requires attendanceStartSlotId + attendanceEndSlotId (the actual
     * contiguous credited range, inside the schedule span). ABSENT requires
     * both to be null. Attended periods are derived server-side.
     */
    public record AttendanceEntry(
            @NotNull UUID studentId,
            @NotNull AttendanceStatus attendanceStatus,
            String remark,
            UUID attendanceStartSlotId,
            UUID attendanceEndSlotId
    ) {}
}
