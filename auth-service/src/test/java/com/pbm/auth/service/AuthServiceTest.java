package com.pbm.auth.service;

import com.pbm.auth.config.JwtUtil;
import com.pbm.auth.domain.User;
import com.pbm.auth.dto.request.ChangePasswordRequest;
import com.pbm.auth.dto.request.LoginRequest;
import com.pbm.auth.dto.request.SignupRequest;
import com.pbm.auth.dto.response.TokenResponse;
import com.pbm.auth.dto.response.UserResponse;
import com.pbm.auth.exception.AuthException;
import com.pbm.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthService의 핵심 인증 비즈니스 로직을 단위 테스트한다.
 *
 * 역할: DB/Redis/JWT 같은 외부 의존성을 Mock으로 대체해 서비스 로직만 검증
 * 동작: 입력값 준비 -> Mock 동작 정의 -> 서비스 메서드 호출 -> 결과/상호작용 검증
 * 연관: UserRepository, PasswordEncoder, JwtUtil, RedisTemplate
 */
/*
    서비스 안에는 수많은 외부 도구들이 있다. 만약 테스트할 때마다 진짜 DB에 연결하면 너무 느리고, 데이터가 꼬이기 십상이다.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    // userRepository, RedisTemplate 등을 전부 껍데기만 있는 Mock데이터를 만듦
    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    // 우리가 테스트할 AuthService를 만들고 거기에 Mock데이터를 집어넣음
    @InjectMocks
    private AuthService authService;

    @Test
    @DisplayName("회원가입: 이미 존재하는 이메일이면 AuthException(emailAlreadyExists)을 던진다")
    void signup_duplicateEmail_throwsAuthException() {
        // given: 이미 가입된 이메일로 회원가입 요청이 들어온 상황
        SignupRequest request = new SignupRequest("dup@pbm.com", "password123", "tester");
        when(userRepository.existsByEmail(request.email())).thenReturn(true);

        // when & then: 중복 가입을 막기 위해 CONFLICT 예외가 발생해야 한다
        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(AuthException.class)
                .hasMessage("이미 사용 중인 이메일입니다.");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("회원가입: 성공 시 비밀번호를 인코딩하여 저장하고 UserResponse를 반환한다")
    void signup_success_encodesPasswordAndReturnsUserResponse() {
        // given: 중복이 아닌 이메일이며 비밀번호 인코딩이 가능한 상황
        SignupRequest request = new SignupRequest("new@pbm.com", "password123", "newbie");
        when(userRepository.existsByEmail(request.email())).thenReturn(false);
        when(passwordEncoder.encode(request.password())).thenReturn("encoded-password");

        User savedUser = createUser(1L, "new@pbm.com", "encoded-password", "newbie", User.Role.USER);
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        // when: 회원가입 로직 수행
        UserResponse response = authService.signup(request);

        // then: 저장된 엔티티의 비밀번호가 원문이 아닌 인코딩 값인지 검증
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User capturedUser = userCaptor.getValue();

        assertThat(capturedUser.getEmail()).isEqualTo("new@pbm.com");
        assertThat(capturedUser.getPassword()).isEqualTo("encoded-password");
        assertThat(capturedUser.getRole()).isEqualTo(User.Role.USER);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo("new@pbm.com");
        assertThat(response.nickname()).isEqualTo("newbie");
        assertThat(response.role()).isEqualTo("USER");
    }

    @Test
    @DisplayName("로그인: 비밀번호가 일치하지 않으면 AuthException(invalidCredentials)을 던진다")
    void login_invalidCredentials_throwsAuthException() {
        // given: 이메일로 사용자는 찾았지만 입력 비밀번호가 다른 상황
        LoginRequest request = new LoginRequest("user@pbm.com", "wrong-password");
        User user = createUser(1L, "user@pbm.com", "encoded-real-password", "tester", User.Role.USER);

        when(userRepository.findByEmail(request.email())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(request.password(), user.getPassword())).thenReturn(false);

        // when & then: 인증 실패 예외가 발생해야 하며 토큰 발급은 수행되지 않아야 한다
        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(AuthException.class)
                .hasMessage("이메일 또는 비밀번호가 올바르지 않습니다.");

        verify(jwtUtil, never()).generateAccessToken(any(), any());
        verify(jwtUtil, never()).generateRefreshToken(any());
    }

    /*
        실제 Redis를 연결하지 않고 단위 테스트를 진행하는 거임.
     */
    @Test
    @DisplayName("로그인: 성공 시 JWT 발급 후 Refresh Token을 Redis에 저장한다")
    void login_success_generatesJwtAndStoresRefreshTokenInRedis() {
        // given: 올바른 이메일/비밀번호로 로그인하는 정상 시나리오
        LoginRequest request = new LoginRequest("user@pbm.com", "plain-password");
        User user = createUser(1L, "user@pbm.com", "encoded-password", "tester", User.Role.USER);

        when(userRepository.findByEmail(request.email())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(request.password(), user.getPassword())).thenReturn(true);
        when(jwtUtil.generateAccessToken(1L, "USER")).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken(1L)).thenReturn("refresh-token");
        when(jwtUtil.getRefreshExpiration()).thenReturn(1_209_600_000L);
        when(jwtUtil.getAccessExpiration()).thenReturn(3_600_000L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // when: 로그인 로직 수행
        TokenResponse response = authService.login(request);

        // then: 응답 토큰 정보와 Redis 저장 동작(키/값/TTL/단위)을 모두 검증
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3_600_000L);

        verify(valueOperations).set(
                eq("RT:1"),
                eq("refresh-token"),
                eq(1_209_600_000L),
                eq(TimeUnit.MILLISECONDS)
        );
    }

    @Test
    @DisplayName("로그아웃: 사용자 ID 기준으로 Redis의 Refresh Token을 삭제한다")
    void logout_deletesRefreshTokenFromRedis() {
        // when: 로그아웃 수행
        authService.logout(1L);

        // then: 해당 사용자 키의 Refresh Token이 제거되어 재발급이 불가능해야 한다
        verify(redisTemplate).delete("RT:1");
    }

    @Test
    @DisplayName("토큰 재발급: 전달된 Refresh Token이 유효하지 않으면 AuthException(invalidToken)을 던진다")
    void refresh_invalidToken_throwsAuthException() {
        // given: JWT 자체가 만료/서명오류 등으로 유효하지 않은 상황
        when(jwtUtil.isValid("invalid-token")).thenReturn(false);

        // when & then: Redis 조회 전에 즉시 예외가 발생해야 한다
        assertThatThrownBy(() -> authService.refresh("invalid-token"))
                .isInstanceOf(AuthException.class)
                .hasMessage("유효하지 않은 토큰입니다.");

        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("토큰 재발급: Redis 저장 토큰과 요청 토큰이 다르면 AuthException(invalidToken)을 던진다")
    void refresh_redisMismatch_throwsAuthException() {
        // given: JWT 형식은 유효하지만 Redis에 저장된 토큰과 일치하지 않는 상황
        String requestRefreshToken = "refresh-token-from-client";
        when(jwtUtil.isValid(requestRefreshToken)).thenReturn(true);
        when(jwtUtil.getUserId(requestRefreshToken)).thenReturn(1L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("RT:1")).thenReturn("different-token-in-redis");

        // when & then: 토큰 탈취/재사용을 막기 위해 인증 실패 처리되어야 한다
        assertThatThrownBy(() -> authService.refresh(requestRefreshToken))
                .isInstanceOf(AuthException.class)
                .hasMessage("유효하지 않은 토큰입니다.");
    }

    @Test
    @DisplayName("토큰 재발급: 성공 시 Access/Refresh를 새로 발급하고 Redis를 로테이션한다")
    void refresh_success_rotatesTokenAndReturnsNewTokenResponse() {
        // given: 유효한 refresh 토큰이며 Redis 값과도 일치하는 정상 재발급 상황
        String oldRefreshToken = "old-refresh-token";
        User user = createUser(1L, "user@pbm.com", "encoded-password", "tester", User.Role.USER);

        when(jwtUtil.isValid(oldRefreshToken)).thenReturn(true);
        when(jwtUtil.getUserId(oldRefreshToken)).thenReturn(1L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("RT:1")).thenReturn(oldRefreshToken);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(jwtUtil.generateAccessToken(1L, "USER")).thenReturn("new-access-token");
        when(jwtUtil.generateRefreshToken(1L)).thenReturn("new-refresh-token");
        when(jwtUtil.getRefreshExpiration()).thenReturn(1_209_600_000L);
        when(jwtUtil.getAccessExpiration()).thenReturn(3_600_000L);

        // when: 토큰 재발급 수행
        TokenResponse response = authService.refresh(oldRefreshToken);

        // then: 새 토큰 반환 + Redis 저장값도 새 refresh 토큰으로 교체되어야 한다
        assertThat(response.accessToken()).isEqualTo("new-access-token");
        assertThat(response.refreshToken()).isEqualTo("new-refresh-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3_600_000L);

        verify(valueOperations).set(
                eq("RT:1"),
                eq("new-refresh-token"),
                eq(1_209_600_000L),
                eq(TimeUnit.MILLISECONDS)
        );
    }

    @Test
    @DisplayName("내 정보 조회: 존재하지 않는 사용자 ID면 AuthException(userNotFound)을 던진다")
    void getMyInfo_userNotFound_throwsAuthException() {
        // given: DB에 존재하지 않는 사용자 ID
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        // when & then: 조회 실패 예외를 던져 클라이언트에 404로 매핑되도록 한다
        assertThatThrownBy(() -> authService.getMyInfo(999L))
                .isInstanceOf(AuthException.class)
                .hasMessage("사용자를 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("내 정보 조회: 존재하는 사용자면 UserResponse를 반환한다")
    void getMyInfo_success_returnsUserResponse() {
        // given: DB에 존재하는 사용자 ID
        User user = createUser(1L, "user@pbm.com", "encoded-password", "tester", User.Role.USER);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        // when: 내 정보 조회 수행
        UserResponse response = authService.getMyInfo(1L);

        // then: 엔티티가 API 응답 DTO로 올바르게 변환되어야 한다
        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo("user@pbm.com");
        assertThat(response.nickname()).isEqualTo("tester");
        assertThat(response.role()).isEqualTo("USER");
    }

    @Test
    @DisplayName("비밀번호 변경: 현재 비밀번호가 일치하면 새 비밀번호로 변경한다.")
    void changPassword_success_encodesAndUpdatesPassword(){
        // given
        User user = createUser(1L, "user@pbm.com", "encoded-old", "tester", User.Role.USER);
        ChangePasswordRequest request = new ChangePasswordRequest("oldPassword", "newPassword1");

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("oldPassword", "encoded-old")).thenReturn(true);
        when(passwordEncoder.encode("newPassword1")).thenReturn("encoded-new");

        //when
        authService.changePassword(1L, request);

        //then
        // User.changePassword()가 호출됐는지 확인 -> user.getPassword()로 검증
        assertThat(user.getPassword()).isEqualTo("encoded-new");
    }

    @Test
    @DisplayName("비밀번호 변경: 현재 비밀번호가 틀리면 AuthException(wrongPassword)을 던진다")
    void changePassword_wrongCurrentPassword_throwsAuthException() {
        User user = createUser(1L, "user@pbm.com", "encoded-old", "tester", User.Role.USER);
        ChangePasswordRequest request = new ChangePasswordRequest("wrongPassword", "newPassword1");

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongPassword", "encoded-old")).thenReturn(false);

        assertThatThrownBy(() -> authService.changePassword(1L, request))
                .isInstanceOf(AuthException.class)
                .hasMessage("현재 비밀번호가 일치하지 않습니다.");
    }

    @Test
    @DisplayName("비밀번호 변경: 사용자가 존재하지 않으면 AuthException(userNotFound)을 던진다")
    void changePassword_userNotFound_throwsAuthException() {
        ChangePasswordRequest request = new ChangePasswordRequest("any", "newPassword1");
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.changePassword(999L, request))
                .isInstanceOf(AuthException.class)
                .hasMessage("사용자를 찾을 수 없습니다.");
    }

    // 테스트에서 필요한 User 엔티티를 간결하게 만들기 위한 헬퍼 메서드
    private User createUser(Long id, String email, String password, String nickname, User.Role role) {
        User user = User.builder()
                .email(email)
                .password(password)
                .nickname(nickname)
                .role(role)
                .build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
