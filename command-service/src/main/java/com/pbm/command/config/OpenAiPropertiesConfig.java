package com.pbm.command.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAI 프록시 호출용 설정 프로퍼티 등록 클래스.
 *
 * 역할: `openai.*` 설정값을 OpenAiProperties Bean으로 바인딩하여
 *       다른 컴포넌트가 의존성 주입으로 사용할 수 있게 한다.
 * 동작: application.yml의 `openai.base-url`, timeout 값 등을 읽어 Bean으로 등록한다.
 * 연관: OpenAiProperties, OpenAiCommandClient.
 */
@Configuration
@EnableConfigurationProperties(OpenAiProperties.class)
public class OpenAiPropertiesConfig {
}
