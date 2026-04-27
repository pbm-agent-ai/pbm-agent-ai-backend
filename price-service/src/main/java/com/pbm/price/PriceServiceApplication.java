package com.pbm.price;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 가격 모니터링 서비스 메인 애플리케이션
 * - 네이버 쇼핑 API를 통한 상품 가격 검색
 * - Claude API 연동 가격 추이 분석 (추후 확장)
 * - Eureka 서비스 디스커버리 등록
 */
@EnableDiscoveryClient
@SpringBootApplication
public class PriceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PriceServiceApplication.class, args);
    }
}
