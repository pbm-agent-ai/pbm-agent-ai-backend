package com.pbm.command.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * 브라우저 에이전트 토큰 유틸리티.
 *
 * 역할: command-service에서 pairing/device/agent 토큰을 검증하고 발급한다.
 * 동작: auth-service / gateway와 동일한 JWT 시크릿을 공유하여 토큰 타입별 claim을 해석한다.
 * 연관: BrowserDeviceService, JwtAuthenticationFilter.
 */
@Component
public class BrowserAgentTokenUtil {

    private final SecretKey secretKey;
    private final long deviceTokenExpiration;
    private final long agentTokenExpiration;

    public BrowserAgentTokenUtil(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.device-expiration}") long deviceTokenExpiration,
            @Value("${jwt.agent-expiration}") long agentTokenExpiration
    ) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.deviceTokenExpiration = deviceTokenExpiration;
        this.agentTokenExpiration = agentTokenExpiration;
    }

    /**
     * 장기 device token을 발급한다.
     */
    public String generateDeviceToken(Long userId, String deviceId) {
        return Jwts.builder()
                .subject(deviceId)
                .claim("role", "DEVICE")
                .claim("userId", userId)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + deviceTokenExpiration))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 특정 run 실행용 단기 agent token을 발급한다.
     */
    public String generateAgentToken(Long userId, String runId, String deviceId) {
        return Jwts.builder()
                .subject(runId)
                .claim("role", "AGENT")
                .claim("userId", userId)
                .claim("deviceId", deviceId)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + agentTokenExpiration))
                .signWith(secretKey)
                .compact();
    }

    /**
     * auth-service가 발급한 pairing token에서 userId를 추출한다.
     */
    public Long getPairingUserId(String token) {
        if (!isValid(token)) {
            throw new JwtException("유효하지 않은 pairing token입니다.");
        }

        String role = getRole(token);
        if (!"PAIRING".equals(role)) {
            throw new JwtException("pairing token이 아닙니다.");
        }
        return Long.parseLong(getClaims(token).getSubject());
    }

    public boolean isValid(String token) {
        try {
            getClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public String getRole(String token) {
        String role = getClaims(token).get("role", String.class);
        if (role == null || role.isBlank()) {
            throw new JwtException("권한 정보가 없는 토큰입니다.");
        }
        return role;
    }

    public long getDeviceTokenExpiration() {
        return deviceTokenExpiration;
    }

    public long getAgentTokenExpiration() {
        return agentTokenExpiration;
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
