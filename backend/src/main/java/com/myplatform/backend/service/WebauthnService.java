package com.myplatform.backend.service;

import com.myplatform.backend.config.WebauthnProperties;
import com.myplatform.backend.dto.webauthn.AuthenticationOptionsResponse;
import com.myplatform.backend.dto.webauthn.AuthenticationVerifyRequest;
import com.myplatform.backend.dto.webauthn.RegisteredCredentialDto;
import com.myplatform.backend.dto.webauthn.RegistrationOptionsResponse;
import com.myplatform.backend.dto.webauthn.RegistrationVerifyRequest;
import com.myplatform.backend.entity.User;
import com.myplatform.backend.entity.WebauthnChallenge;
import com.myplatform.backend.entity.WebauthnCredential;
import com.myplatform.backend.repository.UserRepository;
import com.myplatform.backend.repository.WebauthnChallengeRepository;
import com.myplatform.backend.repository.WebauthnCredentialRepository;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecord;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticationRequest;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.RegistrationRequest;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import com.webauthn4j.util.Base64UrlUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Transactional
@Slf4j
public class WebauthnService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CHALLENGE_BYTES = 32;
    private static final int USER_HANDLE_BYTES = 32;
    private static final long CHALLENGE_TTL_MIN = 5;

    private final WebauthnProperties props;
    private final UserRepository userRepository;
    private final WebauthnCredentialRepository credentialRepository;
    private final WebauthnChallengeRepository challengeRepository;

    private final WebAuthnManager manager = WebAuthnManager.createNonStrictWebAuthnManager();
    private final ObjectConverter objectConverter = new ObjectConverter();
    private final AttestedCredentialDataConverter attestedCredentialDataConverter =
            new AttestedCredentialDataConverter(objectConverter);

    public WebauthnService(WebauthnProperties props,
                           UserRepository userRepository,
                           WebauthnCredentialRepository credentialRepository,
                           WebauthnChallengeRepository challengeRepository) {
        this.props = props;
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.challengeRepository = challengeRepository;
    }

    // ============================================================
    //  REGISTRATION
    // ============================================================

    public RegistrationOptionsResponse startRegistration(User user) {
        byte[] challenge = randomBytes(CHALLENGE_BYTES);
        // 신규 사용자 핸들 또는 기존 재사용 (한 사용자 = 하나의 user handle)
        byte[] userHandle = credentialRepository.findAllByUserId(user.getId()).stream()
                .findFirst()
                .map(WebauthnCredential::getUserHandle)
                .orElseGet(() -> randomBytes(USER_HANDLE_BYTES));

        saveChallenge(user.getUsername(), WebauthnChallenge.Ceremony.REGISTER, challenge, user.getId());

        List<Map<String, Object>> excludeCredentials = credentialRepository.findAllByUserId(user.getId()).stream()
                .map(c -> credDescriptor(c.getCredentialId(), c.getTransports()))
                .toList();

        return new RegistrationOptionsResponse(
                Base64UrlUtil.encodeToString(challenge),
                new RegistrationOptionsResponse.Rp(props.getRpId(), props.getRpName()),
                new RegistrationOptionsResponse.User(
                        Base64UrlUtil.encodeToString(userHandle),
                        user.getUsername(),
                        user.getName()
                ),
                List.of(
                        new RegistrationOptionsResponse.PubKeyParam("public-key", -7),   // ES256
                        new RegistrationOptionsResponse.PubKeyParam("public-key", -257)  // RS256
                ),
                props.getTimeoutMs(),
                "none",
                new RegistrationOptionsResponse.AuthenticatorSelection(
                        "platform",        // 내장 인증기 (지문/Face ID)
                        "required",        // 반드시 사용자 인증
                        "required",        // 패스키(discoverable credential) 강제 — 사용자명 없이 로그인 가능
                        true
                ),
                excludeCredentials
        );
    }

    public RegisteredCredentialDto finishRegistration(User user, RegistrationVerifyRequest req) {
        WebauthnChallenge stored = requireValidChallenge(user.getUsername(), WebauthnChallenge.Ceremony.REGISTER);

        byte[] attestationObject = Base64UrlUtil.decode(req.response().attestationObject());
        byte[] clientDataJSON = Base64UrlUtil.decode(req.response().clientDataJSON());

        ServerProperty serverProperty = new ServerProperty(
                new Origin(props.getOrigin()),
                props.getRpId(),
                new DefaultChallenge(stored.getChallenge())
        );

        RegistrationRequest registrationRequest = new RegistrationRequest(attestationObject, clientDataJSON);
        RegistrationParameters registrationParameters = new RegistrationParameters(
                serverProperty,
                List.of(
                        new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
                        new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256)
                ),
                true,  // userVerificationRequired
                true   // userPresenceRequired
        );

        RegistrationData data;
        try {
            data = manager.parse(registrationRequest);
            manager.validate(data, registrationParameters);
        } catch (Exception e) {
            throw new IllegalStateException("등록 검증 실패: " + e.getMessage(), e);
        }

        AttestedCredentialData attested = data.getAttestationObject().getAuthenticatorData().getAttestedCredentialData();
        if (attested == null) {
            throw new IllegalStateException("인증기 자격증명 데이터가 없습니다.");
        }

        byte[] credentialId = attested.getCredentialId();
        byte[] serializedPublicKey = attestedCredentialDataConverter.convert(attested);
        long signCount = data.getAttestationObject().getAuthenticatorData().getSignCount();

        // 이미 등록된 credential 이면 덮어쓰기 방지 (idempotent 하게 리턴)
        Optional<WebauthnCredential> existing = credentialRepository.findByCredentialId(credentialId);
        if (existing.isPresent()) {
            return toDto(existing.get());
        }

        // 이 사용자의 기존 userHandle 재사용, 없으면 새로
        byte[] userHandle = credentialRepository.findAllByUserId(user.getId()).stream()
                .findFirst()
                .map(WebauthnCredential::getUserHandle)
                .orElseGet(() -> randomBytes(USER_HANDLE_BYTES));

        WebauthnCredential cred = new WebauthnCredential();
        cred.setUserId(user.getId());
        cred.setCredentialId(credentialId);
        cred.setPublicKey(serializedPublicKey);
        cred.setSignCount(signCount);
        cred.setTransports(req.response().transports() == null ? null : String.join(",", req.response().transports()));
        cred.setAaguid(attested.getAaguid() == null ? null : attested.getAaguid().getBytes());
        cred.setDeviceName(req.deviceName() != null && !req.deviceName().isBlank() ? req.deviceName() : defaultDeviceName(req));
        cred.setUserHandle(userHandle);
        boolean be = data.getAttestationObject().getAuthenticatorData().isFlagBE();
        boolean bs = data.getAttestationObject().getAuthenticatorData().isFlagBS();
        cred.setBackupEligible(be);
        cred.setBackupState(bs);

        credentialRepository.save(cred);
        challengeRepository.deleteBySessionKeyAndCeremony(user.getUsername(), WebauthnChallenge.Ceremony.REGISTER);
        return toDto(cred);
    }

    // ============================================================
    //  AUTHENTICATION
    // ============================================================

    /**
     * 패스키(discoverable credential) 로그인 — 사용자명 없이 시작.
     * 브라우저가 OS 에 등록된 패스키 목록을 띄워주고, 사용자가 선택 + 생체인증.
     * sessionKey 는 challenge 자체를 base64url 한 값.
     */
    public AuthenticationOptionsResponse startPasskeyAuthentication() {
        byte[] challenge = randomBytes(CHALLENGE_BYTES);
        saveChallenge(passkeySessionKey(challenge), WebauthnChallenge.Ceremony.LOGIN, challenge, null);
        return new AuthenticationOptionsResponse(
                Base64UrlUtil.encodeToString(challenge),
                props.getRpId(),
                props.getTimeoutMs(),
                "required",
                List.of() // empty allowCredentials → 브라우저가 OS 패스키 picker 표시
        );
    }

    /**
     * 패스키 로그인 검증 — 사용자명 받지 않고 credentialId 로 사용자 식별.
     */
    public User finishPasskeyAuthentication(AuthenticationVerifyRequest req) {
        byte[] credentialId = Base64UrlUtil.decode(req.id());
        byte[] clientDataJSON = Base64UrlUtil.decode(req.response().clientDataJSON());
        byte[] authenticatorData = Base64UrlUtil.decode(req.response().authenticatorData());
        byte[] signature = Base64UrlUtil.decode(req.response().signature());

        // clientDataJSON 에서 challenge 추출 → 그걸로 저장된 ceremony 찾기
        byte[] challengeFromClient = extractChallenge(clientDataJSON);
        WebauthnChallenge stored = requireValidChallenge(
                passkeySessionKey(challengeFromClient), WebauthnChallenge.Ceremony.LOGIN);

        WebauthnCredential cred = credentialRepository.findByCredentialId(credentialId)
                .orElseThrow(() -> new IllegalStateException("등록되지 않은 패스키입니다."));
        User user = userRepository.findById(cred.getUserId())
                .orElseThrow(() -> new IllegalStateException("사용자를 찾을 수 없습니다."));

        AttestedCredentialData attested = attestedCredentialDataConverter.convert(cred.getPublicKey());
        CredentialRecord credentialRecord = new CredentialRecordImpl(
                null, null,
                cred.isBackupEligible(), cred.isBackupState(),
                cred.getSignCount(), attested,
                null, null, null, null
        );

        ServerProperty serverProperty = new ServerProperty(
                new Origin(props.getOrigin()),
                props.getRpId(),
                new DefaultChallenge(stored.getChallenge())
        );

        AuthenticationRequest authenticationRequest = new AuthenticationRequest(
                credentialId, authenticatorData, clientDataJSON, signature
        );
        AuthenticationParameters authenticationParameters = new AuthenticationParameters(
                serverProperty,
                credentialRecord,
                List.of(credentialId),
                true, true
        );

        AuthenticationData authData;
        try {
            authData = manager.parse(authenticationRequest);
            manager.validate(authData, authenticationParameters);
        } catch (Exception e) {
            throw new IllegalStateException("패스키 인증 검증 실패: " + e.getMessage(), e);
        }

        // W3C WebAuthn signCount 복제 탐지 — stored > 0 인데 new <= stored 면 복제 의심.
        // platform authenticator (TouchID/FaceID 등) 는 signCount 를 항상 0 으로 두므로 stored=0 이면 검증 안 함.
        long newSignCount = authData.getAuthenticatorData().getSignCount();
        long storedSignCount = cred.getSignCount();
        if (storedSignCount > 0 && newSignCount <= storedSignCount) {
            log.warn("[WebAuthn] signCount 복제 의심: user={}, stored={}, received={}",
                    user.getUsername(), storedSignCount, newSignCount);
            throw new IllegalStateException("인증 검증 실패: 복제된 인증기로 의심됩니다. 관리자에게 문의하세요.");
        }
        cred.setSignCount(newSignCount);
        cred.setLastUsedAt(LocalDateTime.now());
        credentialRepository.save(cred);

        challengeRepository.deleteBySessionKeyAndCeremony(
                passkeySessionKey(challengeFromClient), WebauthnChallenge.Ceremony.LOGIN);
        return user;
    }

    public AuthenticationOptionsResponse startAuthentication(String username) {
        byte[] challenge = randomBytes(CHALLENGE_BYTES);

        Optional<User> userOpt = userRepository.findByUsername(username);
        List<Map<String, Object>> allowCredentials = new ArrayList<>();
        Long userId = null;
        if (userOpt.isPresent()) {
            userId = userOpt.get().getId();
            allowCredentials = credentialRepository.findAllByUserId(userId).stream()
                    .map(c -> credDescriptor(c.getCredentialId(), c.getTransports()))
                    .toList();
        }

        saveChallenge(username, WebauthnChallenge.Ceremony.LOGIN, challenge, userId);

        return new AuthenticationOptionsResponse(
                Base64UrlUtil.encodeToString(challenge),
                props.getRpId(),
                props.getTimeoutMs(),
                "required",
                allowCredentials
        );
    }

    public User finishAuthentication(String username, AuthenticationVerifyRequest req) {
        WebauthnChallenge stored = requireValidChallenge(username, WebauthnChallenge.Ceremony.LOGIN);

        byte[] credentialId = Base64UrlUtil.decode(req.id());
        byte[] clientDataJSON = Base64UrlUtil.decode(req.response().clientDataJSON());
        byte[] authenticatorData = Base64UrlUtil.decode(req.response().authenticatorData());
        byte[] signature = Base64UrlUtil.decode(req.response().signature());

        WebauthnCredential cred = credentialRepository.findByCredentialId(credentialId)
                .orElseThrow(() -> new IllegalStateException("등록되지 않은 인증기입니다."));

        User user = userRepository.findById(cred.getUserId())
                .orElseThrow(() -> new IllegalStateException("사용자를 찾을 수 없습니다."));

        if (!user.getUsername().equals(username)) {
            throw new IllegalStateException("사용자와 인증기가 일치하지 않습니다.");
        }

        AttestedCredentialData attested = attestedCredentialDataConverter.convert(cred.getPublicKey());
        CredentialRecord credentialRecord = new CredentialRecordImpl(
                null,                    // attestationStatement
                null,                    // uvInitialized
                cred.isBackupEligible(),
                cred.isBackupState(),
                cred.getSignCount(),
                attested,
                null,                    // authenticator extensions
                null,                    // collected client data
                null,                    // client extensions
                null                     // transports
        );

        ServerProperty serverProperty = new ServerProperty(
                new Origin(props.getOrigin()),
                props.getRpId(),
                new DefaultChallenge(stored.getChallenge())
        );

        AuthenticationRequest authenticationRequest = new AuthenticationRequest(
                credentialId, authenticatorData, clientDataJSON, signature
        );
        AuthenticationParameters authenticationParameters = new AuthenticationParameters(
                serverProperty,
                credentialRecord,
                List.of(credentialId),   // allowCredentials
                true,                    // userVerificationRequired
                true                     // userPresenceRequired
        );

        AuthenticationData authData;
        try {
            authData = manager.parse(authenticationRequest);
            manager.validate(authData, authenticationParameters);
        } catch (Exception e) {
            throw new IllegalStateException("인증 검증 실패: " + e.getMessage(), e);
        }

        // W3C signCount 복제 탐지 (위 finishPasskeyAuthentication 동일 패턴)
        long newSignCount = authData.getAuthenticatorData().getSignCount();
        long storedSignCount = cred.getSignCount();
        if (storedSignCount > 0 && newSignCount <= storedSignCount) {
            log.warn("[WebAuthn] signCount 복제 의심: user={}, stored={}, received={}",
                    user.getUsername(), storedSignCount, newSignCount);
            throw new IllegalStateException("인증 검증 실패: 복제된 인증기로 의심됩니다. 관리자에게 문의하세요.");
        }
        cred.setSignCount(newSignCount);
        cred.setLastUsedAt(LocalDateTime.now());
        credentialRepository.save(cred);

        challengeRepository.deleteBySessionKeyAndCeremony(username, WebauthnChallenge.Ceremony.LOGIN);
        return user;
    }

    // ============================================================
    //  CREDENTIAL 관리
    // ============================================================

    public List<RegisteredCredentialDto> listCredentials(Long userId) {
        return credentialRepository.findAllByUserId(userId).stream()
                .map(this::toDto)
                .toList();
    }

    public void deleteCredential(Long userId, Long credentialRowId) {
        credentialRepository.deleteByIdAndUserId(credentialRowId, userId);
    }

    // ============================================================
    //  내부 헬퍼
    // ============================================================

    private WebauthnChallenge requireValidChallenge(String sessionKey, WebauthnChallenge.Ceremony ceremony) {
        WebauthnChallenge ch = challengeRepository
                .findFirstBySessionKeyAndCeremonyOrderByCreatedAtDesc(sessionKey, ceremony)
                .orElseThrow(() -> new IllegalStateException("진행 중인 " + ceremony + " ceremony 가 없습니다. 다시 시도하세요."));
        if (ch.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalStateException("요청이 만료되었습니다. 다시 시도하세요.");
        }
        return ch;
    }

    private void saveChallenge(String sessionKey, WebauthnChallenge.Ceremony ceremony, byte[] challenge, Long userId) {
        // 같은 세션/ceremony 기존 것 정리
        challengeRepository.deleteBySessionKeyAndCeremony(sessionKey, ceremony);
        WebauthnChallenge ch = new WebauthnChallenge();
        ch.setSessionKey(sessionKey);
        ch.setCeremony(ceremony);
        ch.setChallenge(challenge);
        ch.setUserId(userId);
        ch.setExpiresAt(LocalDateTime.now().plusMinutes(CHALLENGE_TTL_MIN));
        challengeRepository.save(ch);
    }

    private byte[] randomBytes(int len) {
        byte[] b = new byte[len];
        RANDOM.nextBytes(b);
        return b;
    }

    private Map<String, Object> credDescriptor(byte[] credentialId, String transports) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", "public-key");
        m.put("id", Base64UrlUtil.encodeToString(credentialId));
        if (transports != null && !transports.isBlank()) {
            m.put("transports", List.of(transports.split(",")));
        }
        return m;
    }

    private RegisteredCredentialDto toDto(WebauthnCredential c) {
        return new RegisteredCredentialDto(
                c.getId(),
                c.getDeviceName(),
                c.getCreatedAt(),
                c.getLastUsedAt()
        );
    }

    private String defaultDeviceName(RegistrationVerifyRequest req) {
        if (req.response().transports() != null && req.response().transports().contains("internal")) {
            return "내 기기";
        }
        return "보안 키";
    }

    /** 패스키 ceremony 의 sessionKey — challenge 자체를 base64url + prefix */
    private static String passkeySessionKey(byte[] challenge) {
        return "pk:" + Base64UrlUtil.encodeToString(challenge);
    }

    /** clientDataJSON 에서 "challenge" 필드만 뽑아 base64url 디코드 */
    private static byte[] extractChallenge(byte[] clientDataJSON) {
        String json = new String(clientDataJSON, java.nio.charset.StandardCharsets.UTF_8);
        // clientDataJSON 은 "challenge":"<base64url>" 형식이 항상 포함됨
        int i = json.indexOf("\"challenge\"");
        if (i < 0) throw new IllegalStateException("clientDataJSON 에 challenge 가 없습니다.");
        int start = json.indexOf('"', json.indexOf(':', i) + 1) + 1;
        int end = json.indexOf('"', start);
        if (start <= 0 || end <= start) throw new IllegalStateException("challenge 파싱 실패");
        return Base64UrlUtil.decode(json.substring(start, end));
    }
}
