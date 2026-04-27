package com.pbm.notification.service;

import com.pbm.notification.dto.event.PriceAlertEvent;
import com.pbm.notification.dto.event.PriceAlertEventPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 가격 알림 이벤트를 받아 포맷팅된 메시지를 생성하고 발송처리기에 전달하는 서비스.
 *
 * 역할: Kafka 컨슈머(PriceAlertConsumer)와 실제 발송 채널(NotificationSender) 사이의 중간 계층이다.
 * 동작:
 *   1. PriceAlertConsumer로부터 PriceAlertEvent를 전달받는다.
 *   2. 이벤트 페이로드를 기반으로 사용자가 읽기 쉬운 알림 메시지 문자열을 생성한다.
 *   3. 생성한 메시지를 NotificationSender 구현체에 전달해 실제 발송을 위임한다.
 * 연관: PriceAlertConsumer(호출자), NotificationSender(발송 위임 대상).
 *
 * 분리 이유:
 * - 컨슈머는 Kafka 수신만 담당하고, 메시지 생성 로직은 서비스에 집중시킨다.
 * - 메시지 포맷 변경 시 컨슈머 코드를 수정할 필요가 없다.
 * - 발송 채널 교체 시 서비스 코드를 수정할 필요가 없다 (OCP 준수).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    // 알림 발송을 위임할 발송처리기 (콘솔, 텔레그램, 이메일 등)
    private final NotificationSender notificationSender;

    /**
     * 가격 알림 이벤트를 처리하여 포맷팅된 메시지를 발송처리기에 전달한다.
     *
     * @param event price-service가 발행한 가격 알림 이벤트 (Envelope + Payload)
     *
     * 처리 흐름:
     * 1. 이벤트 메타데이터(eventId, eventType)를 로그로 기록한다.
     * 2. 페이로드 정보를 바탕으로 알림 메시지 문자열을 생성한다.
     * 3. 생성한 메시지를 NotificationSender에 전달한다.
     */
    public void handlePriceAlert(PriceAlertEvent event) {
        // 이벤트 메타데이터 로깅 (수신 확인용)
        log.info("[가격알림 수신] eventId={}, eventType={}, producer={}",
                event.eventId(), event.eventType(), event.producer());

        // 페이로드에서 알림 메시지를 생성하고 발송처리기에 전달
        String message = formatPriceAlertMessage(event.payload());
        notificationSender.send(message);
    }

    /**
     * PriceAlertEventPayload를 사용자가 읽기 쉬운 알림 메시지 문자열로 변환한다.
     *
     * @param payload 가격 알림 페이로드 (userId, productName, currentPrice, targetPrice, productUrl)
     * @return 포맷팅된 알림 메시지 문자열
     *
     * 포맷 예시:
     * 💰 가격 알림
     * 사용자 ID: 1
     * 상품명: 아이폰 16 Pro 256GB
     * 현재 가격: 1,200,000원
     * 목표 가격: 1,100,000원
     * 상품 URL: https://example.com/iphone16pro
     */
    private String formatPriceAlertMessage(PriceAlertEventPayload payload) {
        // StringBuilder를 사용해 알림 메시지를 조립한다
        StringBuilder sb = new StringBuilder();

        // 알림 제목 (이모지 + 제목)
        sb.append("💰 가격 알림\n");

        // 사용자 ID (누구의 목표 가격인지 식별)
        sb.append("사용자 ID: ").append(payload.userId()).append("\n");

        // 상품명 (어떤 상품의 가격이 목표에 도달했는지)
        sb.append("상품명: ").append(payload.productName()).append("\n");

        // 현재 가격 (목표 가격에 도달한 현재 시점 가격)
        sb.append("현재 가격: ").append(formatPrice(payload.currentPrice())).append("원\n");

        // 목표 가격 (사용자가 설정한 희망 가격)
        sb.append("목표 가격: ").append(formatPrice(payload.targetPrice())).append("원\n");

        // 상품 URL (알림에서 바로 상품 페이지로 이동할 수 있는 링크)
        if (payload.productUrl() != null && !payload.productUrl().isBlank()) {
            sb.append("상품 URL: ").append(payload.productUrl());
        }

        return sb.toString();
    }

    /**
     * 가격(정수)을 읽기 쉬운 포맷으로 변환한다.
     * 예: 1200000 → "1,200,000"
     *
     * @param price 원 단위 가격 (정수)
     * @return 콤마가 포함된 포맷팅된 가격 문자열
     */
    private String formatPrice(Integer price) {
        // null인 경우 빈 문자열 반환 (안전 처리)
        if (price == null) {
            return "";
        }
        // String.format으로 천 단위 콤마를 추가한다
        return String.format("%,d", price);
    }
}