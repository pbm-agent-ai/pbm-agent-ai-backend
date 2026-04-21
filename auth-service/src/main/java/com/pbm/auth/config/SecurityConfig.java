package com.pbm.auth.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    // 역할: 어떤 요청을 열어둘지, 어떤 필터로 인증할지 시큐리티 전체 흐름을 결정
    // 수정: JWT 필터와 401 JSON 응답 엔트리포인트를 연결해 실제 인증 체인을 완성
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 이 서비스는 세션 로그인/기본 인증이 아니라 JWT기반 API서버이기 때문에 끈다.
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(session ->
                        // 서버 세션을 안 쓴다. 매 요청마다 토큰으로 인증.
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 인증 실패 시 응답 규칙을 JwtAuthenticationEntryPoint로 고정
                // 그래서 401이 항상 JSON으로 나감
                .exceptionHandling(exception ->
                        exception.authenticationEntryPoint(jwtAuthenticationEntryPoint))
                // JWT 필터를 기본 로그인 필터보다 앞에서 실행.
                // 즉, 컨트롤러/인가 판단 전에 JWT인증 정보를 SecurityContext에 미리 넣어둠.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/signup", "/api/auth/login", "/api/auth/refresh",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
