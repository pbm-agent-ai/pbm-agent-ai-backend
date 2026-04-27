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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

/*
    - 클래스 레벨 @Transactional(readOnly = true)라 기본은 조회 전용이고, 쓰기 작업 메서드(signup/login/logout/refresh)에만 @Transactional을 따로 붙여 변경 트랜잭션을 연다.
    - 의존성 5개:
      - UserRepository: 사용자 조회/저장
      - PasswordEncoder: 비밀번호 해시/검증
      - JwtUtil: access/refresh 생성·검증·클레임 파싱
      - RedisTemplate<String, String>: refresh 토큰 저장/조회/삭제
      - KafkaEventPublisher: 로그인 성공 이벤트 비동기 발행 (@Async)

  */
@Slf4j
@Service
@RequiredArgsConstructor  // final 필드 생성자 자동 생성 (= 의존성 주입)
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RedisTemplate<String, String> redisTemplate;
    private final KafkaEventPublisher kafkaEventPublisher;

    /*
        메서드별 역할
    - signup(...) (AuthService.java:32)
      - 이메일 중복 검사 후 비밀번호 암호화해서 사용자 저장
      - 중복이면 AuthException.emailAlreadyExists() 발생
    - login(...) (AuthService.java:52)
      - 이메일/비밀번호 검증
      - 성공 시 access/refresh 발급
      - refresh 토큰을 Redis에 저장하고 응답 반환
    - logout(...) (AuthService.java:79)
      - Redis에서 해당 사용자 refresh 토큰 삭제
      - 이후 재발급 불가
    - refresh(...) (AuthService.java:86)
      - 전달받은 refresh 토큰 유효성 검사
      - 토큰의 userId 추출
      - Redis에 저장된 값과 완전 일치하는지 확인
      - 일치하면 새 access/refresh 발급 + Redis 값 갱신(토큰 로테이션)
      - getMyInfo(...) (AuthService.java:119)
      - 사용자 정보 조회

     */

    // 역할: 회원가입/로그인/재발급/로그아웃의 핵심 비즈니스 로직 처리
    // 수정: expiresIn 값을 refresh 만료시간이 아니라 access 만료시간 기준으로 정리
    @Transactional
    public UserResponse signup(SignupRequest request) {
        // 이메일 중복 체크
        if (userRepository.existsByEmail(request.email())) {
            throw AuthException.emailAlreadyExists();
        }

        // 비밀번호 암호화 후 저장
        User user = User.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .nickname(request.nickname())
                .role(User.Role.USER)
                .build();

        User savedUser = userRepository.save(user);
        return UserResponse.from(savedUser);
    }

    // 로그인
    @Transactional
    public TokenResponse login(LoginRequest request) {
        // 사용자 조회
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(AuthException::invalidCredentials);

        // 비밀번호 검증
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw AuthException.invalidCredentials();
        }

        // JWT 발급
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getRole().name());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());

        // Refresh Token Redis에 저장 (key: "RT:{userId}")
        redisTemplate.opsForValue().set(
                "RT:" + user.getId(),
                refreshToken,
                jwtUtil.getRefreshExpiration(),
                TimeUnit.MILLISECONDS
        );

        // 로그인 성공 이벤트를 Kafka에 비동기 발행
        // - @Async 메서드이므로 별도 스레드에서 실행되어 호출자(로그인) 스레드를 블로킹하지 않는다
        // - Kafka 브로커 장애 시에도 로그인 자체는 성공해야 한다(best-effort)
        // - try-catch로 감싸 @Async 프록시가 정상 동작하지 않는 환경(단위 테스트 등)에서도 안전하게 보호
        try {
            kafkaEventPublisher.publishLoginSuccessEvent(user);
        } catch (Exception e) {
            // Kafka 이벤트 발행 실패가 로그인 응답에 영향을 주지 않도록 예외를 삼킨다
            log.warn("로그인 성공 이벤트 발행 중 오류 발생: userId={}, 원인={}", user.getId(), e.getMessage());
        }

        return new TokenResponse(accessToken, refreshToken, "Bearer", jwtUtil.getAccessExpiration());
    }

    // 로그아웃
    @Transactional
    public void logout(Long userId) {
        // Redis에서 Refresh Token 삭제
        redisTemplate.delete("RT:" + userId);
    }

    // 토큰 재발급
    @Transactional
    public TokenResponse refresh(String refreshToken) {
        // 토큰 유효성 검사
        if (!jwtUtil.isValid(refreshToken)) {
            throw AuthException.invalidToken();
        }

        Long userId = jwtUtil.getUserId(refreshToken);

        // Redis에 저장된 Refresh Token과 비교
        String savedToken = redisTemplate.opsForValue().get("RT:" + userId);
        if (!refreshToken.equals(savedToken)) {
            throw AuthException.invalidToken();
        }

        User user = userRepository.findById(userId)
                .orElseThrow(AuthException::userNotFound);

        // 새 토큰 발급
        String newAccessToken = jwtUtil.generateAccessToken(user.getId(), user.getRole().name());
        String newRefreshToken = jwtUtil.generateRefreshToken(user.getId());

        // Redis 갱신
        redisTemplate.opsForValue().set(
                "RT:" + userId,
                newRefreshToken,
                jwtUtil.getRefreshExpiration(),
                TimeUnit.MILLISECONDS
        );

        return new TokenResponse(newAccessToken, newRefreshToken, "Bearer", jwtUtil.getAccessExpiration());
    }

    // 내 정보 조회
    public UserResponse getMyInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(AuthException::userNotFound);
        return UserResponse.from(user);
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request){
        // 1. 사용자 조회
        User user = userRepository.findById(userId)
                .orElseThrow(AuthException::userNotFound);

        // 2. 현재 비밀번호 검증
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())){
            throw AuthException.wrongPassword();
        }

        // 3. 새 비밀번호 암호화 후 변경
        user.changePassword(passwordEncoder.encode(request.newPassword()));
    }
}
