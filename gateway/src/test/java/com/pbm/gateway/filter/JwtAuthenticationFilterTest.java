package com.pbm.gateway.filter;

import com.pbm.gateway.config.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// JwtAuthenticationFilter의 헤더 스푸핑 방지 및 경로별 인증 정책을 검증하는 테스트
@SpringBootTest
// 테스트용 JWT 시크릿과 Eureka 비활성화 설정 주입
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-must-be-at-least-256bits-long",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false"
})
public class JwtAuthenticationFilterTest {

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
                .get("/api/v1/auth/login")
                .build();
        // WebFlux 환경에서 가짜 요청/교환 객체 생성
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        // WebFlux의 Mono 결과를 검증하는 리액티브 전용 테스트 도구(reactor-test 의존성 필요)
        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // 공개 경로도 스푸핑 방지를 위해 요청을 mutate하므로 any()로 검증
        verify(chain).filter(any());
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
                .get("/api/v1/commands/parse")
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
                .get("/api/v1/commands/parse")
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
                .get("/api/v1/commands/parse")
                .header("Authorization", "Bearer " + expiredToken)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assert exchange.getResponse().getStatusCode().value() == 401;
    }

    @Test
    @DisplayName("공개 경로 요청 시 클라이언트가 보낸 X-User-Id / X-User-Role 스푸핑 헤더를 제거한다")
    void publicPath_shouldStripSpoofedUserHeaders() {
        // 클라이언트가 X-User-Id=999, X-User-Role=ADMIN 을 위조하여 보낸 상황
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/auth/login")
                .header("X-User-Id", "999")
                .header("X-User-Role", "ADMIN")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        ArgumentCaptor<ServerWebExchange> exchangeCaptor = ArgumentCaptor.forClass(ServerWebExchange.class);
        when(chain.filter(exchangeCaptor.capture())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // 공개 경로에서도 X-User-Id / X-User-Role 헤더가 제거되어 다운스트림으로 전달되어야 한다
        ServerHttpRequest forwardedRequest = exchangeCaptor.getValue().getRequest();
        HttpHeaders headers = forwardedRequest.getHeaders();
        assertNull(headers.getFirst("X-User-Id"), "공개 경로에서 X-User-Id 헤더가 제거되지 않았습니다");
        assertNull(headers.getFirst("X-User-Role"), "공개 경로에서 X-User-Role 헤더가 제거되지 않았습니다");
    }

    @Test
    @DisplayName("인증된 경로에서 스푸핑된 X-User-Id / X-User-Role을 제거하고 JWT의 신뢰값으로 교체한다")
    void authenticatedPath_shouldReplaceSpoofedHeadersWithTrustedValues() {
        // JWT는 userId=1, role=USER 로 발급된 토큰
        String token = Jwts.builder()
                .subject("1")
                .claim("role", "USER")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 1800000))
                .signWith(testSecretKey)
                .compact();

        // 클라이언트가 X-User-Id=999, X-User-Role=ADMIN 을 위조하여 함께 보낸 상황
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/commands/parse")
                .header("Authorization", "Bearer " + token)
                .header("X-User-Id", "999")
                .header("X-User-Role", "ADMIN")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        ArgumentCaptor<ServerWebExchange> exchangeCaptor = ArgumentCaptor.forClass(ServerWebExchange.class);
        when(chain.filter(exchangeCaptor.capture())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // 다운스트림으로 전달된 요청에는 위조된 헤더 대신 JWT에서 추출한 신뢰값만 존재해야 한다
        ServerHttpRequest forwardedRequest = exchangeCaptor.getValue().getRequest();
        HttpHeaders headers = forwardedRequest.getHeaders();
        assertEquals("1", headers.getFirst("X-User-Id"), "JWT의 userId로 교체되지 않았습니다");
        assertEquals("USER", headers.getFirst("X-User-Role"), "JWT의 role로 교체되지 않았습니다");
    }
}
