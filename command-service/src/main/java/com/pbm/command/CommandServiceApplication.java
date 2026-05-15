package com.pbm.command;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * command-service 애플리케이션 시작 클래스.
 *
 * 역할: Spring Boot 애플리케이션을 시작하고 command-service의 컴포넌트 스캔 기준점을 제공한다.
 * 동작: main 메서드가 실행되면 Spring 컨테이너를 띄우고 Controller, Service, Config Bean을 등록한다.
 * 연관: CommandParseController, CommandExecutionService.
 */
@SpringBootApplication
// JPA Auditing을 활성화하여 @CreatedDate, @LastModifiedDate가 런타임에서도 자동 기록되게 한다.
@EnableJpaAuditing
public class CommandServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommandServiceApplication.class, args);
    }
}
