package com.pbm.command.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI API 호출 설정 프로퍼티.
 *
 * 역할: command-service가 external-api-service의 OpenAI 프록시를 호출할 때 필요한 URL과 timeout 값을 주입받는다.
 * 동작: `openai.*` prefix로 바인딩되며, OpenAiCommandClient가 프록시 호출에 사용한다.
 * 연관: OpenAiCommandClient.
 */
@ConfigurationProperties(prefix = "proxy")
public class OpenAiProperties {

    private String baseUrl = "http://127.0.0.1:8090";
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 10000;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
