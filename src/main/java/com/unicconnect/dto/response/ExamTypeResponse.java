package com.unicconnect.dto.response;

import java.util.UUID;

public record ExamTypeResponse(
        UUID examTypeId,
        String examTypeName
) {}