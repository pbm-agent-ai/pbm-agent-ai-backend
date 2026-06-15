package com.pbm.notification.service;

import com.pbm.notification.dto.event.PriceAlertEvent;
import com.pbm.notification.dto.event.PriceAlertEventPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * PriceAlertConsumer 단위 테스트.
 *
 * 역할: Kafka 컨슈머의 consume() 메서드가 수신한 이벤트를 NotificationService에 정확히 위임하는지 검증한다.
 * 동작: consume() 호출 시 NotificationService.handlePriceAlert()가 정확히 1회 호출되는지 확인한다.
 * 연관: PriceAlertConsumer, NotificationService.
 *
 * 검증 관점:
 * - 컨슈머는 수신만 담당하므로, NotificationService 이외의 의존성을 갖지 않는다.
 * - 컨슈머는 이벤트를 변환하거나 수정하지 않고 그대로 서비스에 전달한다.
 */
@ExtendWith(MockitoExtension.class)
class PriceAlertConsumerTest {

    // NotificationService 모의 객체 (컨슈머의 유일한 의존성)
    @Mock
    private NotificationService notificationService;

    // 테스트 대상: 모의 NotificationService가 주입된 컨슈머
    @InjectMocks
    private PriceAlertConsumer priceAlertConsumer;

    /**
     * 정상 이벤트를 수신했을 때 consume()이 NotificationService.handlePriceAlert()를 호출하는지 검증한다.
     *
     * 검증 내용:
     * 1. notificationService.handlePriceAlert(event)가 정확히 1회 호출되었는지 확인
     * 2. 불필요한 추가 호출이 없는지 확인
     */
    @Test
    @DisplayName("정상: 가격 알림 이벤트 수신 시 NotificationService.handlePriceAlert()를 호출한다")
    void consume_validEvent_callsNotificationService() {
        // given: price-service가 발행할 수 있는 정상 가격 알림 이벤트
        PriceAlertEventPayload payload = new PriceAlertEventPayload(
                1L,                    // userId: 알림 대상 사용자
                "아이폰 16 Pro 256GB",  // productName: 상품명
                1200000,               // currentPrice: 현재 가격 (1,200,000원)
                1100000,               // targetPrice: 목표 가격 (1,100,000원)
                "https://example.com/iphone16pro",  // productUrl: 상품 페이지
                "아이폰 16 프로"        // searchKeyword: 검색 키워드
        );
        PriceAlertEvent event = new PriceAlertEvent(
                "evt-test-001",        // eventId: 이벤트 고유 ID
                "PRICE_ALERT",         // eventType: 이벤트 종류
                Instant.now(),          // occurredAt: 이벤트 발생 시각
                "price-service",       // producer: 이벤트 발행 서비스
                payload
        );

        // when: 컨슈머가 이벤트를 수신하여 처리
        priceAlertConsumer.consume(event);

        // then: NotificationService.handlePriceAlert()가 이벤트와 함께 정확히 1회 호출되어야 한다
        verify(notificationService).handlePriceAlert(event);
        // 불필요한 추가 호출이 없는지 확인
        verifyNoMoreInteractions(notificationService);
    }

    /**
     * null 필드가 포함된 이벤트도 컨슈머가 NotificationService에 그대로 전달하는지 검증한다.
     *
     * 검증 내용:
     * - null productUrl이 포함된 이벤트도 예외 없이 서비스에 전달된다
     * - 컨슈머는 이벤트의 내용을 검증/변환하지 않고 그대로 위임한다
     */
    @Test
    @DisplayName("정상: null 필드가 포함된 이벤트도 NotificationService에 전달한다")
    void consume_eventWithNullFields_callsNotificationService() {
        // given: 일부 필드가 null인 이벤트 (예: productUrl이 없는 경우)
        PriceAlertEventPayload payload = new PriceAlertEventPayload(
                2L,
                "갤럭시 S25 Ultra",
                1300000,
                1200000,
                null,  // productUrl이 없는 경우
                "갤럭시"
        );
        PriceAlertEvent event = new PriceAlertEvent(
                "evt-test-002",
                "PRICE_ALERT",
                Instant.now(),
                "price-service",
                payload
        );

        // when: 컨슈머가 null 필드가 포함된 이벤트를 수신
        priceAlertConsumer.consume(event);

        // then: NotificationService에 이벤트가 그대로 전달되어야 한다
        verify(notificationService).handlePriceAlert(event);
        verifyNoMoreInteractions(notificationService);
    }
}