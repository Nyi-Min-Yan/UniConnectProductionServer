package com.unicconnect.dto.response;

import java.util.List;

public record ImportResultResponse(
        int created,
        List<ImportError> errors
) {}
