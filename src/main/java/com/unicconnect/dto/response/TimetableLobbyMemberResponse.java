package com.unicconnect.dto.response;

import java.time.Instant;
import java.util.UUID;

public record TimetableLobbyMemberResponse(
        UUID memberId,
        UUID staffId,
        String staffNo,
        String staffName,
        String unitName,
        Instant invitedAt,
        Instant joinedAt,
        boolean joined
) {}
