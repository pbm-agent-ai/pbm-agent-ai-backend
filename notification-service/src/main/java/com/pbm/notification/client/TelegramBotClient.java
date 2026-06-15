package com.pbm.notification.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Telegram Bot API 클라이언트.
 *
 * 역할: 텔레그램 봇을 통해 사용자에게 메시지를 전송한다.
 * 동작: Telegram Bot API의 sendMessage 엔드포인트를 호출한다.
 * 연관: NotificationDispatcher.
 *
 * 외부 의존: Telegram Bot API (https://api.telegram.org)
 * 환경변수: TELEGRAM_BOT_TOKEN
 */
@Slf4j
@Component
public class TelegramBotClient {

    private final RestTemplate restTemplate;
    private final String botToken;
    private final String apiUrl;

    public TelegramBotClient(
            @Value("${app.telegram.bot-token:}") String botToken,
            @Value("${app.telegram.api-url:https://api.telegram.org}") String apiUrl
    ) {
        this.restTemplate = new RestTemplate();
        this.botToken = botToken;
        this.apiUrl = apiUrl;
    }

    /**
     * 텔레그램 chatId로 메시지를 전송한다.
     *
     * @param chatId 텔레그램 chat ID
     * @param text   전송할 메시지 텍스트
     */
    @CircuitBreaker(name = "telegram", fallbackMethod = "sendMessageFallback")
    public void sendMessage(String chatId, String text) {
        if (botToken == null || botToken.isBlank()) {
            log.warn("[텔레그램] 봇 토큰이 설정되지 않음 - 메시지 발송 건너뜀");
            return;
        }

        String url = String.format("%s/bot%s/sendMessage", apiUrl, botToken);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Telegram sendMessage API payload
        Map<String, Object> body = Map.of(
                "chat_id", chatId,
                "text", text,
                "parse_mode", "HTML"
        );

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        restTemplate.postForEntity(url, request, String.class);

        log.info("[텔레그램 발송 성공] chatId: {}", chatId);
    }

    /**
     * Circuit Breaker fallback: 텔레그램 발송 실패 시 로그만 남긴다.
     */
    @SuppressWarnings("unused")
    private void sendMessageFallback(String chatId, String text, Throwable t) {
        log.error("[텔레그램 발송 실패 - Circuit Breaker fallback] chatId: {}, error: {}",
                chatId, t.getMessage());
    }
}
