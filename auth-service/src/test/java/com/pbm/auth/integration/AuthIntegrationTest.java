package com.pbm.auth.integration;

import com.pbm.auth.dto.request.ChangePasswordRequest;
import com.pbm.auth.dto.request.LoginRequest;
import com.pbm.auth.dto.request.SignupRequest;
import com.pbm.auth.dto.response.TokenResponse;
import com.pbm.auth.dto.response.UserResponse;
import com.pbm.auth.exception.AuthException;
import com.pbm.auth.repository.UserRepository;
import com.pbm.auth.service.AuthService;
import com.pbm.auth.service.KafkaEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * AuthService의 실제 DB(H2)와 Redis 연동 시나리오를 검증하는 통합 테스트.
 *
 * 역할: 인메모리 DB와 Mock RedisTemplate을 사용해 회원가입~로그아웃까지 전체 인증 흐름을 확인한다.
 * 동작: signup -> login -> refresh -> logout 순서로 실행하고 Redis 토큰 저장/삭제 흐름을 검증한다.
 * 연관: AuthService, UserRepository, RedisTemplate.
 *
 * 참고: Testcontainers(macOS Colima 호환성 문제) 대신
 * H2 인메모리 DB와 Mock RedisTemplate을 사용해 외부 인프라 의존을 제거한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-for-auth-integration-tests-must-be-at-least-256-bits-long",
        "jwt.expiration=1800000",
        "jwt.refresh-expiration=604800000",
        "jwt.pairing-expiration=600000"
})
class AuthIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @MockBean
    private RedisTemplate<String, String> redisTemplate;

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);

    private final Map<String, String> refreshTokenStore = new HashMap<>();

// KafkaEventPublisher MockBean: Kafka 이벤트 발행 컴포넌트를 Mock으로 대체하여
// 실제 Kafka 브로커 없이도 컨텍스트 로딩 및 테스트가 가능하다
@MockBean
private KafkaEventPublisher kafkaEventPublisher;

    @BeforeEach
    void setUpRedisMock() {
        refreshTokenStore.clear();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            refreshTokenStore.put(key, value);
            return null;
        }).when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        when(valueOperations.get(anyString()))
                .thenAnswer(invocation -> refreshTokenStore.get(invocation.getArgument(0)));

        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            refreshTokenStore.remove(key);
            return true;
        }).when(redisTemplate).delete(anyString());
    }

    /**
     * 테스트 간 Redis 데이터 충돌을 방지하기 위해 각 테스트 후 사용한 키를 정리한다.
     */
    @AfterEach
    void cleanup() {
        refreshTokenStore.clear();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("통합 플로우: signup -> login -> refresh -> logout 동작과 Redis 토큰 상태를 검증한다")
    void fullAuthFlow_withH2AndMockRedis() {
        // given: 테스트 간 충돌을 피하기 위해 이메일을 매번 고유 값으로 생성한다.
        String email = "user-" + UUID.randomUUID() + "@pbm.com";
        SignupRequest signupRequest = new SignupRequest(email, "password123", "통합테스트유저");

        // when: 1) 회원가입을 수행해 DB에 사용자 레코드가 생성되는지 확인한다.
        UserResponse signupResponse = authService.signup(signupRequest);

        // then: 회원가입 응답이 정상이며, 저장된 사용자를 이메일로 다시 찾을 수 있어야 한다.
        assertThat(signupResponse.id()).isNotNull();
        assertThat(signupResponse.email()).isEqualTo(email);
        Long userId = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("회원가입 사용자를 찾을 수 없습니다."))
                .getId();

        // when: 2) 로그인으로 Access/Refresh 토큰을 발급받는다.
        TokenResponse loginResponse = authService.login(new LoginRequest(email, "password123"));

        // then: 로그인 직후 Refresh 토큰이 Redis에 저장되어 있어야 한다.
        assertThat(loginResponse.accessToken()).isNotBlank();
        assertThat(loginResponse.refreshToken()).isNotBlank();
        assertThat(refreshTokenStore.get("RT:" + userId)).isEqualTo(loginResponse.refreshToken());

        // when: 3) Refresh 토큰으로 재발급을 수행한다.
        TokenResponse refreshResponse = authService.refresh(loginResponse.refreshToken());

        // then: 새 토큰이 발급되고 Redis 저장 토큰도 새 Refresh 토큰으로 교체되어야 한다.
        assertThat(refreshResponse.accessToken()).isNotBlank();
        assertThat(refreshResponse.refreshToken()).isNotBlank();
        // refresh 토큰은 매번 새로 발급되므로 Redis에 저장된 값이 갱신되어 있어야 한다.
        String redisToken = refreshTokenStore.get("RT:" + userId);
        assertThat(redisToken).isEqualTo(refreshResponse.refreshToken());

        // when: 4) 로그아웃을 수행한다.
        authService.logout(userId);

        // then: 로그아웃 후에는 Redis에서 Refresh 토큰이 삭제되어야 한다.
        assertThat(refreshTokenStore.get("RT:" + userId)).isNull();
    }

    @Test
    @DisplayName("통합 플로우: 비밀번호 변경 후 이전 비밀번호로는 로그인이 실패한다")
    void changePassword_thenOldPasswordLoginFails() {
        String email = "pw-" + UUID.randomUUID() + "@pbm.com";
        authService.signup(new SignupRequest(email, "oldPassword1", "테스트유저"));
        TokenResponse loginResp = authService.login(new LoginRequest(email, "oldPassword1"));
        Long userId = userRepository.findByEmail(email).orElseThrow().getId();

        // 비밀번호 변경
        authService.changePassword(userId, new ChangePasswordRequest("oldPassword1", "newPassword1"));

        // 새 비밀번호로 로그인 성공
        TokenResponse newLogin = authService.login(new LoginRequest(email, "newPassword1"));
        assertThat(newLogin.accessToken()).isNotBlank();

        // 이전 비밀번호로 로그인 실패
        assertThatThrownBy(() -> authService.login(new LoginRequest(email, "oldPassword1")))
                .isInstanceOf(AuthException.class)
                .hasMessage("이메일 또는 비밀번호가 올바르지 않습니다.");
    }
}
