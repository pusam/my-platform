package com.myplatform.backend.dto.webauthn;

public record AuthenticationVerifyRequest(
        String id,
        String rawId,
        String type,
        Response response
) {
    public record Response(
            String clientDataJSON,
            String authenticatorData,
            String signature,
            String userHandle
    ) {}
}
