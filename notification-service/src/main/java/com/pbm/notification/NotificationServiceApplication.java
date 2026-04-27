package com.pbm.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * notification-service의 Spring Boot 진입점 클래스.
 *
 * 역할: 알림 서비스 애플리케이션을 부트스트랩하고 Eureka 서비스 디스커버리에 등록한다.
 * 동작: @SpringBootApplication으로 컴포넌트 스캔·자동설정을 활성화하고,
 *       @EnableDiscoveryClient로 Eureka에 자신을 등록한다.
 * 참고: 현재 DB를 사용하지 않으므로 DataSource·JPA 자동설정 제외는 application.yml에서 관리한다.
 */
@EnableDiscoveryClient
@SpringBootApplication
public class NotificationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
