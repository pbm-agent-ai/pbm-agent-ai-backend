package com.pbm.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 콘솔(로그) 출력으로 알림을 전달하는 NotificationSender 임시 구현체.
 *
 * 역할: 실제 외부 채널(텔레그램, 이메일) 연동 전까지 알림 메시지를 로그로 출력하는 임시 발송처리기다.
 * 동작: NotificationService로부터 전달받은 메시지 문자열을 SLF4J 로그(INFO 레벨)로 출력한다.
 * 연관: NotificationSender(인터페이스), NotificationService(호출자).
 *
 * 향후 계획:
 * - 텔레그램 봇 API 연동이 완료되면 TelegramNotificationSender로 대체한다.
 * - 이메일 발송이 필요하면 EmailNotificationSender를 추가한다.
 * - 콘솔 구현체는 개발/디버깅 용도로 계속 유지할 수 있다.
 */
@Slf4j
@Component
public class ConsoleNotificationSender implements NotificationSender {

    /**
     * 전달받은 알림 메시지를 콘솔(로그)에 출력한다.
     *
     * @param message NotificationService에서 포맷팅한 알림 메시지 문자열
     *
     * 출력 예시:
     * [알림 발송] 💰 가격 알림
     * 사용자 ID: 1
     * 상품명: 아이폰 16 Pro 256GB
     * 현재 가격: 1,200,000원
     * 목표 가격: 1,100,000원
     * 상품 URL: https://example.com/iphone16pro
     */
    @Override
    public void send(String message) {
        // 콘솔(로그)로 알림 메시지를 출력한다
        // 실제 운영 환경에서는 텔레그램/이메일 발송으로 교체된다
        log.info("[알림 발송] {}", message);
    }
}