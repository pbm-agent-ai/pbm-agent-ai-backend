package com.pbm.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pbm.auth.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component  // 스프링 빈으로 등록돼서 Security설정에서 주입해서 사용된다.
@RequiredArgsConstructor
/* implements AuthenticationEntryPoint
- 시큐리티 체인에서 "인증 안됨" 상황이 발생하면 commenc( ... )를 호출한다.
- 즉, 인가부족이 아니라 인증 자체가 없는/실패한 40ㅂ처리를 당한다.
 */
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;    // JSON 직렬화용 객체를 DI로 받아서 응답 바디를 JSON으로 쓴다.

    // 역할: 인증 실패를 스프링 기본 HTML 응답 대신 JSON 401로 통일
    // 추가: JWT 필터나 시큐리티가 막은 요청을 프론트가 이해하기 쉬운 형태로 반환
    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        String message = "인증이 필요합니다.";
        if (authException.getMessage() != null && !authException.getMessage().isBlank()) {
            message = authException.getMessage();
        }

        objectMapper.writeValue(response.getWriter(), ApiResponse.error(message));
    }
}
