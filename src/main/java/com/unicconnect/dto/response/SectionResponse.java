package com.unicconnect.dto.response;

import java.util.UUID;

public record SectionResponse(
        UUID sectionId,
        String sectionName
) {}