package com.pbm.auth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/*
이 파일은 JWT 인증의 "실행 담당"임.
JwtAutenticationEntryPoint같은 경우 실패 응답 담당이었다면 이 파일은 요청에서 토큰을 꺼내서 성공/실패를 결정하는 역할임.
 */
@Component
@RequiredArgsConstructor
/* extends OncePerRequestFilter
- 요청당 1번만 실행되는 필터라 JWT검사 용도로 딱 맞다.
- 스프링 시큐리티 필터 체인에서 매 요청마다 동작한다.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {
/* EXCLUDED_PATHS
- signup, login, refresh는 토큰 없이도 접근해야 하므로 필터 검사 제외
- shouldNotFilter()에서 request.getServletPath()로 제외 경로인지 판단
 */
    private static final Set<String> EXCLUDED_PATHS = Set.of(
            "/api/v1/auth/signup",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh"
    );
    /* 의존성
    - JwtUtil jwtUtil: 토큰 유효성 검증/클레임 추출(userId, role)
    - JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint: 토큰 오류 시 401 JSON응답
     */
    private final JwtUtil jwtUtil;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    // 역할: 요청마다 Authorization 헤더의 Bearer 토큰을 검사해 인증 객체를 생성
    // 추가: 이제 /me, /logout 같은 보호된 API가 실제로 JWT 기반으로 통과 가능
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return EXCLUDED_PATHS.contains(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        /* Authorization 헤더 확인
        - 헤더가 없거나 Bearer 형식이 아니면 그냥 다음 필터로 넘김 (doFilter)
        - 여기서 바로 막지 않는 이유는 공개 API일 수도 있고, 최종 인증 판단은 시큐리티 설정이 하기 때문
         */
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Bearer이후 문자열을 잘라 토큰 추출(substring(7))
        String token = authHeader.substring(7);

        /* 토큰 검증
        - jwtUtil.isValid(token)실패시:
            - SecurityContextHolder.clearContext()로 인증 정보 제거
            - jwtAuthenticationEntryPoint.commence( ... )호출
            - "유효하지 않거나 만료된 토큰입니다" 메시지로 401반환
            - 이후 return 으로 체인 종료
         */
        if (!jwtUtil.isValid(token)) {
            SecurityContextHolder.clearContext();
            jwtAuthenticationEntryPoint.commence(
                    request,
                    response,
                    new InsufficientAuthenticationException("유효하지 않거나 만료된 토큰입니다.")
            );
            return;
        }

        /* 인증 객체 생성
        - 검증 성공시 userId, role추출
        - UsernamePasswordAuthenticationToken생성:
            - principal: String.valueOf(userId)
            - credentials: null
            - authorities: Role_ + role(ex.ROLE_USER)
        - 요청 상세 정보(WebAuthenticationDetailSource) 설정
        - SecurityContextHolder.getContext().setAuthentication(autehntication) 저장 -> 클라이언트의 IP 주소와 세션을 쓴다면 세션 ID같은 HTTP요청 자체의 메타데이터를 추출해서 저장함.
        이 정보는 우리 백엔드쪽 스레드에 저장해두는거임.
         */
        Long userId = jwtUtil.getUserId(token);
        String role = jwtUtil.getRole(token);

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        String.valueOf(userId),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))
                );

        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        /* 다음 필터 진행
        - 이증이 세팅된 상태로 filterChain.doFilter( ... ) 호출
        - 이후 컨트롤러/인가 규칙에서 "로그인된 사용자"로 인식됨.
         */
        filterChain.doFilter(request, response);
    }
}
/*
정리하자면 이 필터는 JWT가 있으면 검증하고 맞으면 SecurityContext에 인증 심고, 틀리면 401 JSON을 반환한다.
그래서 /me, /logout같은 보호 API가 동작할 수 있는 기반이 된다.
 */