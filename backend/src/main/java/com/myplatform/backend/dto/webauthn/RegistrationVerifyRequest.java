package com.myplatform.backend.dto.webauthn;

import java.util.List;

/**
 * 브라우저에서 navigator.credentials.create() 결과를 JSON 으로 보낸 것.
 * (@simplewebauthn/browser 의 startRegistration() 반환 포맷에 맞춤)
 */
public record RegistrationVerifyRequest(
        String id,
        String rawId,
        String type,
        Response response,
        String deviceName
) {
    public record Response(
            String attestationObject,
            String clientDataJSON,
            List<String> transports
    ) {}
}
