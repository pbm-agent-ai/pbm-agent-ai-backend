package com.pbm.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * PBM 결제 서비스 메인 애플리케이션.
 * 
 * 역할: Spring Boot 애플리케이션의 진입점으로, 자동 설정과 컴포넌트 스캔을 트리거한다.
 * 연관: Eureka 서비스 디스커버리에 등록되어 gateway의 라우팅 대상이 된다.
 */
@EnableDiscoveryClient
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class PaymentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
