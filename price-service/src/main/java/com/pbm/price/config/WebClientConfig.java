package com.pbm.price.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClient 설정 클래스
 * external-api-service 호출용 비동기 논블로킹 HTTP 클라이언트 빈 등록
 */
@Configuration
public class WebClientConfig {

    /** external-api-service 기본 URL (application.yml에서 주입) */
    @Value("${external-api-service.base-url}")
    private String externalApiBaseUrl;

    /**
     * external-api-service 전용 WebClient 빈
     * 기본 URL과 공통 헤더를 사전 설정한다.
     */
    @Bean
    public WebClient externalApiWebClient() {
        return WebClient.builder()
                .baseUrl(externalApiBaseUrl)
                .build();
    }
}