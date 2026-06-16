package com.pbm.gateway.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

// 게이트웨이 전용 JWT 유틸: 토큰 검증 및 클레임 추출만 담당 (발급 기능 없음)
@Component
public class JwtUtil {

    private final SecretKey secretKey;

    public JwtUtil(@Value("${jwt.secret}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public boolean isValid(String token) {
        try {
            getClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Long getUserId(String token) {
        return Long.parseLong(getClaims(token).getSubject());
    }

    public String getSubject(String token) {
        return getClaims(token).getSubject();
    }

    public String getClaimAsString(String token, String claimName) {
        return getClaims(token).get(claimName, String.class);
    }

    public Object getClaim(String token, String claimName) {
        return getClaims(token).get(claimName);
    }

    public String getRole(String token) {
        String role = getClaims(token).get("role", String.class);
        if (role == null || role.isBlank()) {
            throw new JwtException("권한 정보가 없는 토큰입니다.");
        }
        return role;
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
