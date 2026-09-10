package com.unicconnect.dto.response;

import com.unicconnect.entity.LobbyStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TimetableLobbyResponse(
        UUID lobbyId,
        UUID termId,
        String academicYear,
        UUID leaderStaffId,
        String leaderStaffNo,
        String leaderName,
        LobbyStatus status,
        UUID generationId,
        Instant createdAt,
        List<TimetableLobbyMemberResponse> members
) {}
