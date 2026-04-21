package com.pbm.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbm.auth.dto.request.ChangePasswordRequest;
import com.pbm.auth.dto.request.LoginRequest;
import com.pbm.auth.dto.request.SignupRequest;
import com.pbm.auth.dto.response.TokenResponse;
import com.pbm.auth.dto.response.UserResponse;
import com.pbm.auth.exception.AuthException;
import com.pbm.auth.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AuthController의 HTTP 입출력 계약을 검증하는 통합 테스트.
 *
 * 역할: URL/HTTP 상태코드/JSON 구조(ApiResponse 래퍼)와 예외 매핑을 확인
 * 동작: MockMvc로 요청 전송 -> 컨트롤러 호출 -> 응답 코드/본문 검증
 * 연관: AuthService(Mock), GlobalExceptionHandler
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @Test
    @DisplayName("POST /api/auth/signup: 유효한 요청이면 201과 ApiResponse<UserResponse>를 반환한다")
    void signup_success_returns201WithApiResponse() throws Exception {
        SignupRequest request = new SignupRequest("new@pbm.com", "password123", "newbie");
        UserResponse response = new UserResponse(1L, "new@pbm.com", "newbie", "USER");
        when(authService.signup(any(SignupRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/auth/signup")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("성공"))
                .andExpect(jsonPath("$.data.id").value(1L))
                .andExpect(jsonPath("$.data.email").value("new@pbm.com"))
                .andExpect(jsonPath("$.data.nickname").value("newbie"))
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    @DisplayName("POST /api/auth/signup: 요청값 검증 실패면 400과 ApiResponse 에러 형식을 반환한다")
    void signup_validationFailure_returns400() throws Exception {
        SignupRequest invalidRequest = new SignupRequest("invalid-email", "123", "");

        mockMvc.perform(post("/api/auth/signup")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    @DisplayName("POST /api/auth/login: 유효한 로그인 요청이면 200과 ApiResponse<TokenResponse>를 반환한다")
    void login_success_returns200WithApiResponse() throws Exception {
        LoginRequest request = new LoginRequest("user@pbm.com", "password123");
        TokenResponse response = new TokenResponse("access-token", "refresh-token", "Bearer", 3_600_000L);
        when(authService.login(any(LoginRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("성공"))
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(3_600_000));
    }

    @Test
    @DisplayName("POST /api/auth/login: 인증 실패(AuthException)면 401과 ApiResponse 에러를 반환한다")
    void login_invalidCredentials_returns401() throws Exception {
        LoginRequest request = new LoginRequest("user@pbm.com", "wrong-password");
        when(authService.login(any(LoginRequest.class))).thenThrow(AuthException.invalidCredentials());

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("이메일 또는 비밀번호가 올바르지 않습니다."));
    }

    @Test
    @WithMockUser(username = "1")
    @DisplayName("POST /api/auth/logout: 인증된 사용자는 200과 성공 메시지를 반환한다")
    void logout_success_returns200() throws Exception {
        doNothing().when(authService).logout(1L);

        mockMvc.perform(post("/api/auth/logout").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("로그아웃되었습니다."));
    }

    @Test
    @DisplayName("POST /api/auth/refresh: Refresh-Token 헤더로 재발급 요청 시 200을 반환한다")
    void refresh_success_returns200() throws Exception {
        TokenResponse response = new TokenResponse("new-access", "new-refresh", "Bearer", 3_600_000L);
        when(authService.refresh(eq("refresh-token-value"))).thenReturn(response);

        mockMvc.perform(post("/api/auth/refresh")
                        .with(csrf())
                        .header("Refresh-Token", "refresh-token-value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("성공"))
                .andExpect(jsonPath("$.data.accessToken").value("new-access"))
                .andExpect(jsonPath("$.data.refreshToken").value("new-refresh"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"));
    }

    @Test
    @WithMockUser(username = "1")
    @DisplayName("GET /api/auth/me: 인증된 사용자 정보 조회 시 200과 ApiResponse<UserResponse>를 반환한다")
    void getMyInfo_success_returns200() throws Exception {
        UserResponse response = new UserResponse(1L, "user@pbm.com", "tester", "USER");
        when(authService.getMyInfo(1L)).thenReturn(response);

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("성공"))
                .andExpect(jsonPath("$.data.id").value(1L))
                .andExpect(jsonPath("$.data.email").value("user@pbm.com"))
                .andExpect(jsonPath("$.data.nickname").value("tester"))
                .andExpect(jsonPath("$.data.role").value("USER"));
    }
    @Test
    @WithMockUser(username = "1")
    @DisplayName("PUT /api/auth/password: 인증된 사용자가 올바른 현재 비밀번호로 요청 시 200을 반환한다")
    void changePassword_success_returns200() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest("oldPassword1", "newPassword1");
        doNothing().when(authService).changePassword(eq(1L), any(ChangePasswordRequest.class));

        mockMvc.perform(put("/api/auth/password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("비밀번호가 변경되었습니다."));
    }

    @Test
    @WithMockUser(username = "1")
    @DisplayName("PUT /api/auth/password: 현재 비밀번호가 틀리면 400과 에러 메시지를 반환한다")
    void changePassword_wrongPassword_returns400() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest("wrongPassword", "newPassword1");
        doThrow(AuthException.wrongPassword())
                .when(authService).changePassword(eq(1L), any(ChangePasswordRequest.class));

        mockMvc.perform(put("/api/auth/password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("현재 비밀번호가 일치하지 않습니다."));
    }

    @Test
    @DisplayName("PUT /api/auth/password: 인증 없이 요청하면 401을 반환한다")
    void changePassword_unauthenticated_returns401() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest("old", "newPassword1");

        mockMvc.perform(put("/api/auth/password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

}
