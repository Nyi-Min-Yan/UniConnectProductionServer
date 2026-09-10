package com.unicconnect.dto.response;

import java.util.UUID;

public record SectionInfoResponse(
        UUID sectionId,
        String sectionName
) {}