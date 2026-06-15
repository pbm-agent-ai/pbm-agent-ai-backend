package com.pbm.notification.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Gmail SMTP를 통해 이메일 알림을 발송하는 NotificationSender 구현체.
 *
 * 역할: 사용자에게 가격 충족 알림을 이메일로 전송한다.
 * 동작: JavaMailSender를 사용해 Gmail SMTP 서버로 이메일을 발송한다.
 * 연관: NotificationSender(인터페이스), NotificationDispatcher(호출자).
 *
 * 외부 의존: Gmail SMTP (smtp.gmail.com:587)
 * 환경변수: GMAIL_USERNAME, GMAIL_APP_PASSWORD
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailNotificationSender {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from:noreply@custospay.com}")
    private String fromAddress;

    /**
     * 이메일로 알림 메시지를 발송한다.
     *
     * @param toEmail 수신자 이메일 주소
     * @param subject 이메일 제목
     * @param body    이메일 본문
     */
    @CircuitBreaker(name = "email", fallbackMethod = "sendFallback")
    public void send(String toEmail, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject(subject);
        message.setText(body);

        mailSender.send(message);
        log.info("[이메일 발송 성공] to: {}, subject: {}", toEmail, subject);
    }

    /**
     * Circuit Breaker fallback: 이메일 발송 실패 시 예외를 다시 던져 호출자가 실패를 알 수 있게 한다.
     */
    @SuppressWarnings("unused")
    private void sendFallback(String toEmail, String subject, String body, Throwable t) {
        log.error("[이메일 발송 실패 - Circuit Breaker fallback] to: {}, subject: {}, error: {}",
                toEmail, subject, t.getMessage());
        throw new RuntimeException("이메일 발송 실패: " + t.getMessage(), t);
    }
}
