package com.myplatform.jwtredis.provider;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

public class JwtTokenProvider {

    /** JWT claim 이름 — 토큰 종류(ACCESS/REFRESH) 식별용. */
    public static final String CLAIM_TOKEN_TYPE = "type";
    public static final String TYPE_ACCESS = "ACCESS";
    public static final String TYPE_REFRESH = "REFRESH";
    /**
     * JWT claim 이름 — 로그인(기기/브라우저)마다 발급되는 세션 식별자.
     * RT 를 기기별 Redis 키로 분리해 멀티 디바이스 로그인이 서로의 RT 를 덮어쓰지 않게 한다.
     * 이 claim 이 없는 토큰은 기기 바인딩 전 legacy 토큰으로 취급.
     */
    public static final String CLAIM_DEVICE_ID = "did";

    private final String jwtSecret;
    /** Legacy 단일 만료시간 — backward compat 위해 유지. */
    private final long jwtExpiration;
    private final long accessExpiration;
    private final long refreshExpiration;

    /** Legacy 생성자 — access/refresh 분리 안 된 옛 설정 호환. */
    public JwtTokenProvider(String jwtSecret, long jwtExpiration) {
        this(jwtSecret, jwtExpiration, jwtExpiration, jwtExpiration * 7);
    }

    public JwtTokenProvider(String jwtSecret, long jwtExpiration, long accessExpiration, long refreshExpiration) {
        this.jwtSecret = jwtSecret;
        this.jwtExpiration = jwtExpiration;
        this.accessExpiration = accessExpiration;
        this.refreshExpiration = refreshExpiration;
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(Authentication authentication) {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        return generateToken(userDetails.getUsername());
    }

    /** Legacy 메서드 — type=ACCESS 의 access token 발급으로 동작. backward compat. */
    public String generateToken(String username) {
        return generateAccessToken(username);
    }

    /**
     * 커스텀 TTL(ms) 로 토큰 발급 — SSE 쿼리 파라미터 등 짧게 유효해야 하는 경우용.
     * type 클레임 없이 발급되어 validateAccessToken 도 통과 (legacy 호환).
     */
    public String generateToken(String username, long ttlMs) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + ttlMs);

        return Jwts.builder()
                .setSubject(username)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /** Legacy — 기기 바인딩 없는 access token. 세션 발급 경로는 (username, deviceId) 버전 사용. */
    public String generateAccessToken(String username) {
        return generateAccessToken(username, null);
    }

    public String generateAccessToken(String username, String deviceId) {
        return buildToken(username, accessExpiration, TYPE_ACCESS, deviceId);
    }

    /** Legacy — 기기 바인딩 없는 refresh token. 세션 발급 경로는 (username, deviceId) 버전 사용. */
    public String generateRefreshToken(String username) {
        return generateRefreshToken(username, null);
    }

    public String generateRefreshToken(String username, String deviceId) {
        return buildToken(username, refreshExpiration, TYPE_REFRESH, deviceId);
    }

    private String buildToken(String username, long ttlMs, String type, String deviceId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + ttlMs);

        JwtBuilder builder = Jwts.builder()
                .setSubject(username)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .claim(CLAIM_TOKEN_TYPE, type);
        if (deviceId != null) {
            builder.claim(CLAIM_DEVICE_ID, deviceId);
        }
        return builder
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public String getUsernameFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    /** 토큰의 deviceId(did) claim 추출. 기기 바인딩 전 legacy 토큰·파싱 실패는 null 반환. */
    public String getDeviceIdFromToken(String token) {
        try {
            Object d = parseClaims(token).get(CLAIM_DEVICE_ID);
            return d == null ? null : d.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 토큰 type claim 추출. type 없는 legacy 토큰은 null 반환. */
    public String getTokenType(String token) {
        try {
            Object t = parseClaims(token).get(CLAIM_TOKEN_TYPE);
            return t == null ? null : t.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /** 토큰 서명·만료 검증만. type 검증 안 함 — legacy 호환. */
    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Access Token 검증 — type 이 ACCESS 또는 null(legacy) 인 경우만 통과. REFRESH 는 거부. */
    public boolean validateAccessToken(String token) {
        if (!validateToken(token)) return false;
        String type = getTokenType(token);
        return type == null || TYPE_ACCESS.equals(type);
    }

    /** Refresh Token 검증 — type 이 REFRESH 인 경우만 통과. */
    public boolean validateRefreshToken(String token) {
        if (!validateToken(token)) return false;
        return TYPE_REFRESH.equals(getTokenType(token));
    }

    public long getExpirationTime() {
        return jwtExpiration;
    }

    public long getAccessExpiration() {
        return accessExpiration;
    }

    public long getRefreshExpiration() {
        return refreshExpiration;
    }
}
