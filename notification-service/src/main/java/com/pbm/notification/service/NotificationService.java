package com.pbm.notification.service;

import com.pbm.notification.dto.event.PaymentResultEvent;
import com.pbm.notification.dto.event.PriceAlertEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Kafka 컨슈머로부터 수신한 이벤트를 처리하여 멀티채널로 알림을 발송하는 서비스.
 *
 * 역할:
 *   - PriceAlertConsumer로부터 가격 알림 이벤트(PriceAlertEvent)를 전달받아 처리한다.
 *   - PaymentResultConsumer로부터 결제 결과 이벤트(PaymentResultEvent)를 전달받아 처리한다.
 *   - 메시지 포맷팅은 NotificationMessageFormatter에 위임하고,
 *     실제 발송은 NotificationDispatcher에 위임한다.
 * 동작:
 *   1. 각 Consumer로부터 이벤트를 전달받는다.
 *   2. NotificationMessageFormatter를 통해 이벤트 타입별 알림 메시지를 생성한다.
 *   3. NotificationDispatcher에 userId, subject, message를 전달해 멀티채널 발송을 위임한다.
 * 연관:
 *   PriceAlertConsumer(호출자), PaymentResultConsumer(호출자),
 *   NotificationMessageFormatter(메시지 생성 위임), NotificationDispatcher(발송 위임).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationDispatcher notificationDispatcher;

    private final NotificationMessageFormatter formatter;

    /**
     * 가격 알림 이벤트를 처리하여 포맷팅된 메시지를 멀티채널로 발송한다.
     * eventType에 따라 조건 충족 알림 또는 자동결제 시작 알림으로 분기한다.
     *
     * @param event price-service가 발행한 가격 알림 이벤트 (Envelope + Payload)
     */
    public void handlePriceAlert(PriceAlertEvent event) {
        // 이벤트 메타데이터 로깅 (수신 확인용)
        log.info("[가격알림 수신] eventId={}, eventType={}, producer={}",
                event.eventId(), event.eventType(), event.producer());

        var payload = event.payload();

        // 이메일 제목 생성 (eventType과 상품명 기반)
        String subject = formatter.formatSubject(event.eventType(), payload.productName());

        // eventType에 따라 다른 메시지 포맷팅
        String message;
        if ("AUTO_PAYMENT_START".equals(event.eventType())) {
            // 자동결제 시작 알림 (사용자에게 결제가 시작되었음을 알림)
            message = formatter.formatAutoPaymentStart(payload);
        } else {
            // PRICE_CONDITION_MET 또는 기존 PRICE_ALERT (조건 충족 알림)
            message = formatter.formatConditionMet(payload);
        }

        // 멀티채널 발송 (사용자 설정에 따라 이메일/텔레그램/콘솔)
        notificationDispatcher.dispatch(payload.userId(), subject, message);
    }

    /**
     * 결제 결과 이벤트를 처리하여 포맷팅된 메시지를 멀티채널로 발송한다.
     * eventType에 따라 결제 완료 알림 또는 결제 실패 알림으로 분기한다.
     *
     * @param event payment-service가 발행한 결제 결과 이벤트 (Envelope + Payload)
     */
    public void handlePaymentResult(PaymentResultEvent event) {
        // 이벤트 메타데이터 로깅 (수신 확인용)
        log.info("[결제결과 수신] eventId={}, eventType={}, producer={}",
                event.eventId(), event.eventType(), event.producer());

        var payload = event.payload();

        // 이메일 제목 생성 (eventType 기반, 결제 알림은 상품명 없음)
        String subject = formatter.formatSubject(event.eventType(), null);

        // eventType에 따라 다른 메시지 포맷팅
        String message;
        if ("PAYMENT_FAILED".equals(event.eventType())) {
            // 결제 실패 알림 (실패 사유와 함께 사용자에게 알림)
            message = formatter.formatPaymentFailed(payload);
        } else {
            // PAYMENT_COMPLETED (결제 완료 알림, 금액/수수료/잔액 정보 포함)
            message = formatter.formatPaymentCompleted(payload);
        }

        // 멀티채널 발송 (사용자 설정에 따라 이메일/텔레그램/콘솔)
        notificationDispatcher.dispatch(payload.userId(), subject, message);
    }
}
