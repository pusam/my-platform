package com.myplatform.backend.dto.webauthn;

import java.time.LocalDateTime;

public record RegisteredCredentialDto(
        Long id,
        String deviceName,
        LocalDateTime createdAt,
        LocalDateTime lastUsedAt
) {}
