package com.pbm.price.config;

import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * WebClient 설정 클래스
 * external-api-service 호출용 비동기 논블로킹 HTTP 클라이언트 빈 등록
 *
 * 타임아웃 전략:
 * - Resilience4j @TimeLimiter는 비동기(CompletionStage) 메서드에만 적용 가능하므로,
 *   현재 ExternalApiClient의 동기 block() 방식에서는 사용할 수 없다.
 * - 대신 WebClient 레벨에서 연결 타임아웃과 응답 타임아웃을 설정하여
 *   외부 API 호출 시 무한 대기를 방지한다.
 * - 연결 타임아웃: 3초 (서버와의 TCP 연결 설정 대기 시간)
 * - 응답 타임아웃: 5초 (요청 전송 후 전체 응답 수신 대기 시간)
 */
@Slf4j
@Configuration
public class WebClientConfig {

    // 아래 값들은 application.yml에서 주입 받음
    /** external-api-service 기본 URL (application.yml에서 주입) */
    @Value("${external-api-service.base-url}")
    private String externalApiBaseUrl;

    /** 연결 타임아웃 (밀리초) - 서버와의 TCP 연결 설정 대기 시간 */
    @Value("${external-api-service.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    /** 응답 타임아웃 (초) - 요청 후 전체 응답 수신 대기 시간 */
    @Value("${external-api-service.response-timeout-seconds:5}")
    private int responseTimeoutSeconds;

    /**
     * external-api-service 전용 WebClient 빈
     * 기본 URL, 공통 헤더, 타임아웃 설정을 적용한다.
     *
     * 타임아웃은 Reactor Netty HttpClient 수준에서 설정한다.
     * - CONNECT_TIMEOUT_MILLIS: TCP 연결 설정 시간 제한 (기본 3초)
     * - responseTimeout: HTTP 응답 수신 시간 제한 (기본 5초)
     *   @TimeLimiter 대신 WebClient 레벨 타임아웃을 사용하는 이유:
     *   ExternalApiClient의 메서드가 동기(block()) 방식이므로
     *   @TimeLimiter(비동기 CompletionStage 필요)를 적용할 수 없어
     *   WebClient에서 직접 타임아웃을 설정함
     */
    @Bean
    public WebClient externalApiWebClient() {
        log.info("external-api-service WebClient 생성 - URL: {}, 연결 타임아웃: {}ms, 응답 타임아웃: {}s",
                externalApiBaseUrl, connectTimeoutMs, responseTimeoutSeconds);

        HttpClient httpClient = HttpClient.create()
                // TCP 연결 자체를 맺는데 걸리는 시간 (3초)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                // 연결 후 응답이 완전히 올 때까지 기다리는 시간 (5초)
                .responseTimeout(Duration.ofSeconds(responseTimeoutSeconds));

        return WebClient.builder()
                .baseUrl(externalApiBaseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}