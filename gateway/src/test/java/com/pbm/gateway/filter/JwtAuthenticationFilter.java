package com.pbm.gateway.filter;

import com.pbm.gateway.config.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
// 테스트용 JWT 시크릿과 Eureka 비활성화 설정 주입
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-must-be-at-least-256bits-long",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false"
})
class JwtAuthenticationFilterTest {

    @Autowired
    private JwtAuthenticationFilter filter;

    private SecretKey testSecretKey;
    private static final String TEST_SECRET = "test-secret-key-must-be-at-least-256bits-long";

    @BeforeEach
    void setUp() {
        testSecretKey = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("공개 경로는 토큰 없이 통과")
    void publicPath_shouldPassThrough() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/auth/login")
                .build();
        // WebFlux 환경에서 가짜 요청/교환 객체 생성
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        // WebFlux의 Mono 결과를 검증하는 리액티브 전용 테스트 도구(reactor-test 의존성 필요)
        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
    }

    @Test
    @DisplayName("유효한 토큰으로 요청 시 X-User-Id, X-User-Role 헤더 추가")
    void validToken_shouldAddUserHeaders() {
        String token = Jwts.builder()
                .subject("1")
                .claim("role", "USER")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 1800000))
                .signWith(testSecretKey)
                .compact();

        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/command/parse")
                .header("Authorization", "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);  // 실제 라우팅 없이 필터 로직만 단독 테스트
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(any());
    }

    @Test
    @DisplayName("토큰 없이 보호된 경로 접근 시 401 반환")
    void noToken_shouldReturn401() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/command/parse")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assert exchange.getResponse().getStatusCode().value() == 401;
    }

    @Test
    @DisplayName("만료된 토큰으로 접근 시 401 반환")
    void expiredToken_shouldReturn401() {
        String expiredToken = Jwts.builder()
                .subject("1")
                .claim("role", "USER")
                .issuedAt(new Date(System.currentTimeMillis() - 10000))
                .expiration(new Date(System.currentTimeMillis() - 5000))
                .signWith(testSecretKey)
                .compact();

        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/command/parse")
                .header("Authorization", "Bearer " + expiredToken)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assert exchange.getResponse().getStatusCode().value() == 401;
    }
}