package com.unicconnect.dto.response;

/**
 * Management workspace context for the Timetable page.
 * <p>{@code canManage} is decided server-side from the authenticated staff
 * member's active HOD assignment; the frontend never decides authorization.
 */
public record GenerationManageResponse(
        boolean isHod,
        boolean canManage,
        GenerationSessionResponse generation
) {}
