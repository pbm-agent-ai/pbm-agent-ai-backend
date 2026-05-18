package com.pbm.auth.controller;

import com.pbm.auth.common.ApiResponse;
import com.pbm.auth.dto.request.ChangePasswordRequest;
import com.pbm.auth.dto.request.LoginRequest;
import com.pbm.auth.dto.request.SignupRequest;
import com.pbm.auth.dto.response.PairingTokenResponse;
import com.pbm.auth.dto.response.TokenResponse;
import com.pbm.auth.dto.response.UserResponse;
import com.pbm.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Auth", description = "회원가입, 로그인, 토큰 재발급, 내 정보 조회 API")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    // 역할: 인증 관련 HTTP 요청을 받아 서비스 호출과 응답 반환만 담당
    // 수정: 컨트롤러가 토큰을 직접 해석하지 않고, 시큐리티가 만든 Authentication을 사용
    @Operation(summary = "회원가입", description = "이메일, 비밀번호, 닉네임으로 새 사용자를 생성합니다.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "회원가입 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패", content = @Content)
    })
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<UserResponse>> signup(@Valid @RequestBody SignupRequest request) {
        UserResponse response = authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    @Operation(summary = "로그인", description = "이메일/비밀번호로 로그인하고 access token과 refresh token을 발급합니다.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패", content = @Content)
    })
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request) {
        TokenResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "로그아웃", description = "현재 access token 기준 사용자를 로그아웃 처리합니다.")
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        authService.logout(userId);
        return ResponseEntity.ok(ApiResponse.success("로그아웃되었습니다."));
    }

    @Operation(summary = "토큰 재발급", description = "Refresh-Token 헤더의 refresh token으로 새로운 access token을 발급합니다.")
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(
            @Parameter(description = "로그인 응답으로 받은 refresh token", example = "eyJhbGciOi...")
            @RequestHeader("Refresh-Token") String refreshToken
    ) {
        TokenResponse response = authService.refresh(refreshToken);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 웹 앱에서 현재 로그인한 사용자 기준으로 extension 연결용 pairing token을 발급한다.
     */
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Pairing token 발급", description = "현재 로그인한 사용자 기준으로 브라우저 extension 등록용 pairing token을 발급합니다.")
    @PostMapping("/pairing-token")
    public ResponseEntity<ApiResponse<PairingTokenResponse>> createPairingToken(Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        PairingTokenResponse response = authService.createPairingToken(userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "내 정보 조회", description = "현재 access token 기준 사용자 정보를 조회합니다. Swagger 테스트 시 userId 확인용으로도 사용합니다.")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> getMyInfo(Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        UserResponse response = authService.getMyInfo(userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // 수정: @AuthenticationPrincipal 대신 Authentication을 받아 getName()으로 userId를 추출하도록 변경
    // 이유: @WithMockUser 테스트에서 Authentication.getName()이 username 값을 반환하므로 일관성 유지
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "비밀번호 변경", description = "현재 로그인한 사용자의 비밀번호를 변경합니다.")
    @PutMapping("/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            Authentication authentication,
            @Valid @RequestBody ChangePasswordRequest request
            ) {
        Long userId = Long.valueOf(authentication.getName());
        authService.changePassword(userId, request);
        return ResponseEntity.ok(ApiResponse.success("비밀번호가 변경되었습니다."));
    }
}
