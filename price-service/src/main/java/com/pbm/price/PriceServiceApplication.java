package com.pbm.price;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 가격 모니터링 서비스 메인 애플리케이션
 * - 네이버 쇼핑 API를 통한 상품 가격 검색
 * - Claude API 연동 가격 추이 분석 (추후 확장)
 * - Eureka 서비스 디스커버리 등록
 * - @EnableScheduling으로 백그라운드 가격 수집 스케줄러 활성화
 */
@EnableScheduling
@EnableDiscoveryClient
@SpringBootApplication
public class PriceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PriceServiceApplication.class, args);
    }
}