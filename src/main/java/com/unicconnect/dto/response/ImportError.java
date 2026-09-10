package com.unicconnect.dto.response;

public record ImportError(
        int row,
        String message
) {}
