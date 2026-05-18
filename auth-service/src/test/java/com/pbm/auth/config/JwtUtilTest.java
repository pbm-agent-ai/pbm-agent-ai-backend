package com.pbm.auth.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JwtUtil의 토큰 발급/검증/클레임 추출 로직을 단위 테스트한다.
 *
 * 역할: 스프링 컨텍스트 없이 JwtUtil 생성자를 직접 호출해 순수 로직만 검증
 * 동작: 토큰 생성 -> 파싱/검증 -> subject/claim/예외 동작 확인
 * 연관: io.jsonwebtoken(JJWT)
 */
class JwtUtilTest {

    private static final String SECRET = "test-secret-key-for-jwt-testing-must-be-at-least-256-bits-long";

    @Test
    @DisplayName("generateAccessToken: 토큰이 비어있지 않고 subject(userId)와 role 클레임을 포함한다")
    void generateAccessToken_containsSubjectAndRoleClaim() {
        // given: 액세스 토큰 발급에 필요한 JwtUtil과 사용자 정보
        JwtUtil jwtUtil = new JwtUtil(SECRET, 3_600_000L, 604_800_000L, 600_000L);

        // when: 액세스 토큰 발급
        String token = jwtUtil.generateAccessToken(1L, "USER");

        // then: 토큰 문자열이 존재하고 payload에 userId/role이 정확히 들어가야 한다
        assertThat(token).isNotBlank();

        Claims claims = parseClaims(token, SECRET);
        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(claims.get("role", String.class)).isEqualTo("USER");
    }

    @Test
    @DisplayName("generateRefreshToken: 토큰이 비어있지 않고 subject(userId)를 포함한다")
    void generateRefreshToken_containsSubject() {
        // given
        JwtUtil jwtUtil = new JwtUtil(SECRET, 3_600_000L, 604_800_000L, 600_000L);

        // when
        String token = jwtUtil.generateRefreshToken(7L);

        // then: 리프레시 토큰은 role 없이 userId 중심으로 발급된다
        assertThat(token).isNotBlank();

        Claims claims = parseClaims(token, SECRET);
        assertThat(claims.getSubject()).isEqualTo("7");
    }

    @Test
    @DisplayName("isValid: 정상 토큰은 true를 반환한다")
    void isValid_validToken_returnsTrue() {
        // given
        JwtUtil jwtUtil = new JwtUtil(SECRET, 3_600_000L, 604_800_000L, 600_000L);
        String token = jwtUtil.generateAccessToken(10L, "ADMIN");

        // when
        boolean valid = jwtUtil.isValid(token);

        // then
        assertThat(valid).isTrue();
    }

    @Test
    @DisplayName("isValid: 만료된 토큰은 false를 반환한다")
    void isValid_expiredToken_returnsFalse() {
        // given: expiration을 음수로 주면 생성 시점 기준 이미 만료된 토큰이 만들어진다
        JwtUtil jwtUtil = new JwtUtil(SECRET, -1_000L, 604_800_000L, 600_000L);
        String expiredToken = jwtUtil.generateAccessToken(10L, "USER");

        // when
        boolean valid = jwtUtil.isValid(expiredToken);

        // then
        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("isValid: 다른 시크릿으로 서명된 토큰은 false를 반환한다")
    void isValid_invalidSignature_returnsFalse() {
        // given: 발급자와 검증자의 시크릿이 다르면 서명 검증에 실패해야 한다
        JwtUtil issuer = new JwtUtil(SECRET, 3_600_000L, 604_800_000L, 600_000L);
        JwtUtil verifier = new JwtUtil("another-secret-key-for-signature-mismatch-testing-123456789", 3_600_000L, 604_800_000L, 600_000L);
        String token = issuer.generateAccessToken(11L, "USER");

        // when
        boolean valid = verifier.isValid(token);

        // then
        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("isValid: null 또는 빈 문자열은 false를 반환한다")
    void isValid_nullOrEmpty_returnsFalse() {
        // given
        JwtUtil jwtUtil = new JwtUtil(SECRET, 3_600_000L, 604_800_000L, 600_000L);

        // when & then
        assertThat(jwtUtil.isValid(null)).isFalse();
        assertThat(jwtUtil.isValid("")).isFalse();
    }

    @Test
    @DisplayName("getUserId: 토큰에서 userId를 Long으로 정확히 추출한다")
    void getUserId_extractsCorrectUserId() {
        // given
        JwtUtil jwtUtil = new JwtUtil(SECRET, 3_600_000L, 604_800_000L, 600_000L);
        String token = jwtUtil.generateAccessToken(123L, "USER");

        // when
        Long userId = jwtUtil.getUserId(token);

        // then
        assertThat(userId).isEqualTo(123L);
    }

    @Test
    @DisplayName("getRole: 토큰에서 role을 추출하고 role이 없으면 JwtException을 던진다")
    void getRole_extractsRoleOrThrowsWhenMissing() {
        // given: 액세스 토큰에는 role이 있고, 리프레시 토큰에는 role이 없다
        JwtUtil jwtUtil = new JwtUtil(SECRET, 3_600_000L, 604_800_000L, 600_000L);
        String accessToken = jwtUtil.generateAccessToken(1L, "ADMIN");
        String refreshToken = jwtUtil.generateRefreshToken(1L);

        // when & then: role이 있으면 정상 추출
        assertThat(jwtUtil.getRole(accessToken)).isEqualTo("ADMIN");

        // when & then: role이 없으면 예외로 처리되어야 한다
        assertThatThrownBy(() -> jwtUtil.getRole(refreshToken))
                .isInstanceOf(JwtException.class)
                .hasMessage("권한 정보가 없는 토큰입니다.");
    }

    // 테스트에서 토큰 payload(subject/claim)를 직접 확인하기 위한 헬퍼 메서드
    private Claims parseClaims(String token, String secret) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
