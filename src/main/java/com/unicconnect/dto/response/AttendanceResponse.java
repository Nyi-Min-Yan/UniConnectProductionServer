package com.unicconnect.dto.response;

import com.unicconnect.entity.AttendanceStatus;

import java.time.Instant;
import java.util.UUID;

public record AttendanceResponse(
        UUID attendanceId,
        UUID sessionId,
        UUID studentId,
        String rollNo,
        String studentName,
        AttendanceStatus attendanceStatus,
        String remark,
        Instant markedAt,
        UUID markedByStaffId,
        /** Derived dynamically; never stored. ABSENT => 0. */
        int attendedPeriods,
        UUID attendanceStartSlotId,
        UUID attendanceEndSlotId
) {}
