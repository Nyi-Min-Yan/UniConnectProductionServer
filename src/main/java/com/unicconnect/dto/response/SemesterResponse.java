package com.unicconnect.dto.response;

import java.util.UUID;

public record SemesterResponse(
        UUID semesterId,
        Integer semesterNo
) {}