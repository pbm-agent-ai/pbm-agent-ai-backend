package com.pbm.auth.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 비동기 실행을 활성화하는 설정 클래스.
 *
 * 역할: @Async 어노테이션이 붙은 메서드를 별도 스레드에서 실행하도록 Spring 비동기 인프라를 켠다.
 * 동작: Kafka 이벤트 발행 등 I/O 블로킹이 발생할 수 있는 작업을 호출자 스레드(HTTP 요청 처리)와
 *       분리하여, 브로커 장애 시에도 로그인/회원가입 응답이 지연되지 않도록 보장한다.
 * 연관: KafkaEventPublisher(@Async 메서드)
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}