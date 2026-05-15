package com.pbm.auth.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JwtAuthenticationFilter의 경로 제외/토큰 검증/인증 컨텍스트 설정 동작을 단위 테스트한다.
 *
 * 역할: 시큐리티 필터 내부 분기(헤더 없음/유효/무효)를 각각 독립적으로 검증
 * 동작: Mock 요청/응답/체인 구성 -> 필터 호출 -> SecurityContext 및 collaborator 호출 확인
 * 연관: JwtUtil, JwtAuthenticationEntryPoint
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    @AfterEach
    void clearSecurityContext() {
        // 테스트 간 인증 정보가 섞이지 않도록 매 테스트 후 컨텍스트를 비운다.
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("shouldNotFilter: 회원가입/로그인/재발급 경로는 필터를 건너뛴다")
    void shouldNotFilter_excludedPaths_returnsTrue() {
        // given
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtUtil, jwtAuthenticationEntryPoint);
        List<String> excludedPaths = List.of("/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/refresh");

        for (String path : excludedPaths) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setServletPath(path);

            // when
            boolean shouldSkip = filter.shouldNotFilter(request);

            // then
            assertThat(shouldSkip).isTrue();
        }
    }

    @Test
    @DisplayName("doFilterInternal: Authorization 헤더가 없으면 인증 없이 다음 필터로 전달한다")
    void doFilterInternal_missingAuthorizationHeader_passesThroughChain() throws Exception {
        // given: 보호 API 요청이지만 Authorization 헤더가 없는 상황
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtUtil, jwtAuthenticationEntryPoint);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        // when
        filter.doFilterInternal(request, response, filterChain);

        // then: 체인은 정상 진행되고 SecurityContext 인증 정보는 없어야 한다
        assertThat(filterChain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("doFilterInternal: 유효하지 않은 토큰이면 EntryPoint.commence를 호출한다")
    void doFilterInternal_invalidToken_callsAuthenticationEntryPoint() throws Exception {
        // given: Bearer 토큰은 있으나 JWT 검증에 실패하는 상황
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtUtil, jwtAuthenticationEntryPoint);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer invalid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = new MockFilterChain();

        when(jwtUtil.isValid("invalid-token")).thenReturn(false);

        // when
        filter.doFilterInternal(request, response, filterChain);

        // then: 인증 실패 응답 진입점이 호출되고 SecurityContext는 비워져야 한다
        ArgumentCaptor<InsufficientAuthenticationException> exceptionCaptor =
                ArgumentCaptor.forClass(InsufficientAuthenticationException.class);

        verify(jwtAuthenticationEntryPoint).commence(any(), any(), exceptionCaptor.capture());
        assertThat(exceptionCaptor.getValue().getMessage()).isEqualTo("유효하지 않거나 만료된 토큰입니다.");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("doFilterInternal: 유효한 토큰이면 SecurityContext에 userId와 ROLE_권한을 세팅한다")
    void doFilterInternal_validToken_setsSecurityContextAuthentication() throws Exception {
        // given
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtUtil, jwtAuthenticationEntryPoint);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        when(jwtUtil.isValid("valid-token")).thenReturn(true);
        when(jwtUtil.getUserId("valid-token")).thenReturn(1L);
        when(jwtUtil.getRole("valid-token")).thenReturn("USER");

        // when
        filter.doFilterInternal(request, response, filterChain);

        // then: principal은 userId 문자열, 권한은 ROLE_ 접두사를 붙여 저장되어야 한다
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isEqualTo("1");
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_USER");

        // 체인이 실제로 다음 단계로 전달되었는지도 함께 검증한다.
        assertThat(filterChain.getRequest()).isNotNull();
    }
}
