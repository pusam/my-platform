package com.myplatform.backend.dto.webauthn;

import java.util.List;
import java.util.Map;

/**
 * PublicKeyCredentialCreationOptions — 브라우저 navigator.credentials.create() 로 넘겨주는 값.
 * @simplewebauthn/browser 가 요구하는 JSON 포맷에 맞춤 (바이너리는 base64url).
 */
public record RegistrationOptionsResponse(
        String challenge,
        Rp rp,
        User user,
        List<PubKeyParam> pubKeyCredParams,
        long timeout,
        String attestation,
        AuthenticatorSelection authenticatorSelection,
        List<Map<String, Object>> excludeCredentials
) {
    public record Rp(String id, String name) {}
    public record User(String id, String name, String displayName) {}
    public record PubKeyParam(String type, int alg) {}
    public record AuthenticatorSelection(
            String authenticatorAttachment,
            String userVerification,
            String residentKey,
            boolean requireResidentKey
    ) {}
}
