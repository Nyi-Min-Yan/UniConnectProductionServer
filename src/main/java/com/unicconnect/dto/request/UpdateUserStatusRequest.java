package com.unicconnect.dto.request;

import com.unicconnect.entity.RegistrationStatus;

public record UpdateUserStatusRequest(
        Boolean isActive,
        RegistrationStatus registrationStatus
) {}