package com.myplatform.backend.dto.webauthn;

import java.util.List;
import java.util.Map;

/**
 * PublicKeyCredentialRequestOptions — navigator.credentials.get() 에 넘겨줌.
 */
public record AuthenticationOptionsResponse(
        String challenge,
        String rpId,
        long timeout,
        String userVerification,
        List<Map<String, Object>> allowCredentials
) {}
