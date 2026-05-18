package com.pbm.auth.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
/* JwtUtil: 인증 시스템에서 JWT전담 유틸리티(발급기+검증기+해석기) 역할을 한다.
- 토큰 생성: access/refresh 토큰 발급
- 토큰 검증: 서명/만료 포함 유효성 검사
-클레임 조회: 토큰에서 userId, role 추출
 */
@Component
public class JwtUtil {

    private final SecretKey secretKey;
    private final long expiration;
    private final long refreshExpiration;
    private final long pairingExpiration;

    // 역할: JWT 생성/검증/클레임 조회를 한 곳에서 담당
    // 수정: 필터에서 바로 쓸 수 있도록 role 추출과 access 만료시간 조회를 추가
    public JwtUtil(
            // 생성자, 하드코딩 없이 환경설정 기반으로 동작
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration}") long expiration,
            @Value("${jwt.refresh-expiration}") long refreshExpiration,
            @Value("${jwt.pairing-expiration}") long pairingExpiration
    ) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = expiration;
        this.refreshExpiration = refreshExpiration;
        this.pairingExpiration = pairingExpiration;
    }

    /* 로그인에 성공하면 토큰을 발행해줌
    - subject = userId
    - claim "role"저장
    - 발급시각/만료시각 세팅
    - secretKey로 서명해서 최종 JWT문자열 생성
     */
    public String generateAccessToken(Long userId, String role) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(secretKey)
                .compact();
    }

    /*
    - access보다 보통 더 긴 refresh 만료 시간 사용
    - role없이 userId 중심으로 발급
     */
    public String generateRefreshToken(Long userId) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshExpiration))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 확장프로그램 연결용 pairing token을 발급한다.
     *
     * @param userId 현재 로그인한 사용자 ID
     * @return 단기 pairing token
     */
    public String generatePairingToken(Long userId) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", "PAIRING")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + pairingExpiration))
                .signWith(secretKey)
                .compact();
    }

    // subject를 꺼내 Long으로 변환
    public Long getUserId(String token) {
        return Long.parseLong(getClaims(token).getSubject());
    }

    /*
    - "role" 클레임 추출
    - 없거나 비어있으면 JwtException 발생
    - 필터에서 권한 만들 때 사용
     */
    public String getRole(String token) {
        String role = getClaims(token).get("role", String.class);
        if (role == null || role.isBlank()) {
            throw new JwtException("권한 정보가 없는 토큰입니다.");
        }
        return role;
    }

    /*
    - 내부적으로 getClaims(token) 호출해 파싱 시도
    - 예외 없으면 true, 예외면 false
    - 예외에는 서명 불일치, 만료, 형식 오류 등이 포함
     */
    public boolean isValid(String token) {
        try {
            getClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public long getAccessExpiration() {
        return expiration;
    }

    public long getRefreshExpiration() {
        return refreshExpiration;
    }

    public long getPairingExpiration() {
        return pairingExpiration;
    }

    /*
    - 실제 파싱 핵심
    - verifyWith(secretKey)로 서명 검증
    - parseSignedClaims(token)으로 payload(Claims) 추출
     */
    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
