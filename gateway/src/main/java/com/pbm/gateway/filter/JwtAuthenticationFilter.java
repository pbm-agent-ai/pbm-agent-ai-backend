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
            "/api/v1/auth/signup",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/devices/register",
            "/api/v1/platforms/",          // 플랫폼 설정 조회 (익스텐션이 인증 없이 호출)
            "/api/notifications/telegram/webhook",  // 텔레그램 봇 Webhook (Telegram 서버에서 호출)
            // Swagger UI (개발용)
            "/auth-service/swagger-ui",
            "/auth-service/v3/api-docs",
            "/command-service/swagger-ui",
            "/command-service/v3/api-docs",
            "/price-service/swagger-ui",
            "/price-service/v3/api-docs",
            "/payment-service/swagger-ui",
            "/payment-service/v3/api-docs",
            "/notification-service/swagger-ui",
            "/notification-service/v3/api-docs"
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
            // 스푸핑 방지: 공개 경로 요청에도 클라이언트가 악의적으로 보낸 X-User-Id/X-User-Role 헤더를 제거하고 다음 단계로 넘긴다
            ServerHttpRequest cleanRequest = removeUserIdentityHeaders(exchange.getRequest());
            return chain.filter(exchange.mutate().request(cleanRequest).build());
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
        final String role;
        try {
            role = jwtUtil.getRole(token);
        } catch (RuntimeException e) {
            return writeUnauthorized(exchange, "유효하지 않은 토큰입니다.");
        }

        // 7. 손님 방문 카드에 몰래 적어두기
        // Gateway는 변경 불가능(Immutable)원칙을 따르기 때문.
        // 기존 카드에 펜으로 덧쓰는게 아니라 mutate() (복사본 만듦)를 써서 새로운 카드를 발급
        // 스푸핑 방지: 클라이언트가 보낸 가짜 X-User-Id/X-User-Role을 먼저 지우고, JWT에서 추출한 신뢰값으로 교체
        final ServerHttpRequest mutatedRequest;
        try {
            mutatedRequest = exchange.getRequest().mutate()
                    .headers(headers -> {
                        headers.remove("X-User-Id");
                        headers.remove("X-User-Role");
                        headers.remove("X-Device-Id");
                        headers.remove("X-Run-Id");
                    })
                    .headers(headers -> applyIdentityHeaders(headers, token, role))
                    .build();
        } catch (RuntimeException e) {
            return writeUnauthorized(exchange, "유효하지 않은 토큰입니다.");
        }

        // 8. 메모가 적힌 새로운 방문 카드를 들고 다음 담당자에게 넘김.
        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    // 클라이언트가 임의로 X-User-Id / X-User-Role 헤더를 주입하는 스푸핑 공격을 방지하기 위해
    // 요청에서 해당 헤더를 제거한 복사본을 반환한다.
    private ServerHttpRequest removeUserIdentityHeaders(ServerHttpRequest request) {
        return request.mutate()
                .headers(headers -> {
                    headers.remove("X-User-Id");
                    headers.remove("X-User-Role");
                    headers.remove("X-Device-Id");
                    headers.remove("X-Run-Id");
                })
                .build();
    }

    private void applyIdentityHeaders(org.springframework.http.HttpHeaders headers, String token, String role) {
        headers.set("X-User-Role", role);

        if ("USER".equals(role)) {
            headers.set("X-User-Id", String.valueOf(jwtUtil.getUserId(token)));
            return;
        }

        if ("DEVICE".equals(role)) {
            headers.set("X-Device-Id", jwtUtil.getSubject(token));
            return;
        }

        if ("AGENT".equals(role)) {
            headers.set("X-Run-Id", jwtUtil.getSubject(token));
            headers.set("X-Device-Id", jwtUtil.getClaimAsString(token, "deviceId"));
            return;
        }

        throw new IllegalArgumentException("지원하지 않는 토큰 역할입니다. role=" + role);
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
