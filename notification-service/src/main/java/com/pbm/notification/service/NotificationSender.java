package com.pbm.notification.service;

/**
 * 알림 발송 채널에 대한 추상 인터페이스.
 *
 * 역할: 알림 메시지를 외부 채널(텔레그램, 이메일, 콘솔 등)로 전달하는 역할을 추상화한다.
 * 동작: 구현체는 전달받은 메시지 문자열을 실제 채널로 발송한다.
 * 연관: ConsoleNotificationSender(콘솔 구현체), NotificationService(호출자).
 *
 * 설계 의도:
 * - OCP(개방-폐쇄 원칙)를 따라 새로운 발송 채널 추가 시 기존 코드 수정 없이 구현체만 추가하면 된다.
 * - 현재는 콘솔 출력 구현체만 존재하며, 향후 TelegramNotificationSender, EmailNotificationSender 등을 추가할 수 있다.
 */
public interface NotificationSender {

    /**
     * 포맷팅된 알림 메시지를 외부 채널로 전송한다.
     *
     * @param message NotificationService에서 생성한 포맷팅된 알림 메시지 문자열
     *
     * 호출 흐름:
     * 1. NotificationService가 PriceAlertEvent로부터 메시지를 생성
     * 2. NotificationService가 이 메서드를 호출해 메시지를 전달
     * 3. 구현체가 실제 채널(콘솔, 텔레그램 등)로 메시지를 발송
     */
    void send(String message);
}