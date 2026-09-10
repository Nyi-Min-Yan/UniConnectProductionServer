package com.unicconnect.dto.request;

import com.unicconnect.entity.AttendanceStatus;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Edit/resubmit of one ATTENDANCE row. PRESENT requires the new range ids;
 * ABSENT clears the range. Same validation as initial marking applies.
 */
public record UpdateAttendanceRequest(
        @NotNull AttendanceStatus attendanceStatus,
        String remark,
        UUID attendanceStartSlotId,
        UUID attendanceEndSlotId
) {}
