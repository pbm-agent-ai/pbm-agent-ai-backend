package com.pbm.gateway.filter;

import com.pbm.gateway.config.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

// 모든 요청에 대해 JWT 인증을 처리하는 글로벌 필터
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private final JwtUtil jwtUtil;

    // 인증 없이 접근 가능한 공개 경로
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/signup",
            "/api/auth/login",
            "/api/auth/refresh"
    );

    @Override
    public int getOrder() {
        return -1; // 라우팅 필터보다 먼저 실행
    }

    @Override
    // Gateway는 비동기 방식을 쓰는데 Mono는 '내 필터 검사 작업이 끝났으니 다음 단계로 넘어가도 좋다'는 신호를 보내준다.
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. 요청이 가려는 곳 확인
        String path = exchange.getRequest().getPath().value();

        // 2. 프리패스 구역인지 확인
        if (isPublicPath(path)) {
            return chain.filter(exchange);  // 다음 필터로 곧장 패스
        }

        // 3. 티켓 검사 ("회원가입 아니면, 요청 방문 카드에서 Authorization 을 확인)
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        // 4. 티켓이 없거나 가짜(Bearer로 시작 안함)면 쫓아냄
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return writeUnauthorized(exchange, "인증 토큰이 없습니다.");
        }

        // 5. 티켓 유효성 검증("티켓 번호 조회 및 지났을 경우 내보냄")
        String token = authHeader.substring(7); // "Bearer"글자 떼어내고 진짜 토큰만 추출
        if (!jwtUtil.isValid(token)) {
            return writeUnauthorized(exchange, "유효하지 않은 토큰입니다.");
        }

        // 검증된 사용자 정보를 다운스트림 서비스로 헤더에 전달
        // 6. 정상 티켓 확인 완료. 신분 확인.
        Long userId = jwtUtil.getUserId(token);
        String role = jwtUtil.getRole(token);

        // 7. 손님 방문 카드에 몰래 적어두기
        // Gateway는 변경 불가능(Immutable)원칙을 따르기 때문.
        // 기존 카드에 펜으로 덧쓰는게 아니라 mutate() (복사본 만듦)를 써서 새로운 카드를 발급
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header("X-User-Id", String.valueOf(userId))    // 다른 마이크로서비스가 볼 수 있게 아이디표 붙여줌
                .header("X-User-Role", role)                    // 등급표 붙여줌
                .build();

        // 8. 메모가 적힌 새로운 방문 카드를 들고 다음 담당자에게 넘김.
        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    private Mono<Void> writeUnauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = String.format("{\"success\":false,\"data\":null,\"message\":\"%s\"}", message);
        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}