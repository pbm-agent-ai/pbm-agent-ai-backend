package com.pbm.notification.service;

import com.pbm.notification.dto.event.PriceAlertEvent;
import com.pbm.notification.dto.event.PriceAlertEventPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * NotificationService 단위 테스트.
 *
 * 역할: NotificationService가 PriceAlertEvent를 받아 포맷팅된 메시지를 생성하고
 *       NotificationSender에 전달하는지 검증한다.
 * 동작: handlePriceAlert() 호출 시 이벤트 페이로드로부터 메시지를 생성해 sender.send()를 호출한다.
 * 연관: NotificationService, NotificationSender, PriceAlertEvent, PriceAlertEventPayload.
 *
 * 검증 관점:
 * - 서비스가 sender에 null이 아닌 메시지 문자열을 전달하는지 확인
 * - 포맷팅된 메시지에 핵심 정보(상품명, 가격 등)가 포함되어 있는지 확인
 * - null 필드가 있는 이벤트도 정상 처리되는지 확인
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    // NotificationSender 모의 객체 (실제 발송 채널 대신 검증용)
    @Mock
    private NotificationSender notificationSender;

    // 테스트 대상: 모의 NotificationSender가 주입된 서비스
    @InjectMocks
    private NotificationService notificationService;

    /**
     * 정상 이벤트 처리 테스트 그룹.
     * 모든 필드가 채워진 정상 이벤트를 대상으로 한다.
     */
    @Nested
    @DisplayName("정상 이벤트 처리")
    class NormalEventTests {

        /**
         * handlePriceAlert() 호출 시 NotificationSender.send()가 호출되는지 검증한다.
         *
         * 검증 내용:
         * - sender.send()가 정확히 1회 호출되었는지 확인
         * - 불필요한 추가 호출이 없는지 확인
         */
        @Test
        @DisplayName("정상: 이벤트 처리 시 NotificationSender.send()를 호출한다")
        void handlePriceAlert_callsSenderSend() {
            // given: 모든 필드가 채워진 정상 가격 알림 이벤트
            PriceAlertEventPayload payload = new PriceAlertEventPayload(
                    1L,
                    "아이폰 16 Pro 256GB",
                    1200000,
                    1100000,
                    "https://example.com/iphone16pro"
            );
            PriceAlertEvent event = new PriceAlertEvent(
                    "evt-test-001",
                    "PRICE_ALERT",
                    Instant.now(),
                    "price-service",
                    payload
            );

            // when: 서비스가 이벤트를 처리
            notificationService.handlePriceAlert(event);

            // then: sender.send()가 정확히 1회 호출되어야 한다
            verify(notificationSender).send(anyString());
            verifyNoMoreInteractions(notificationSender);
        }

        /**
         * 포맷팅된 메시지에 핵심 정보가 포함되어 있는지 검증한다.
         *
         * 검증 내용:
         * - 메시지에 상품명이 포함되어 있는지 확인
         * - 메시지에 현재 가격이 포함되어 있는지 확인
         * - 메시지에 목표 가격이 포함되어 있는지 확인
         * - 메시지에 사용자 ID가 포함되어 있는지 확인
         * - 메시지에 상품 URL이 포함되어 있는지 확인
         */
        @Test
        @DisplayName("정상: 포맷팅된 메시지에 상품명, 가격, URL 등 핵심 정보가 포함된다")
        void handlePriceAlert_messageContainsKeyInfo() {
            // given: 핵심 정보가 포함된 이벤트
            PriceAlertEventPayload payload = new PriceAlertEventPayload(
                    1L,
                    "아이폰 16 Pro 256GB",
                    1200000,
                    1100000,
                    "https://example.com/iphone16pro"
            );
            PriceAlertEvent event = new PriceAlertEvent(
                    "evt-test-001",
                    "PRICE_ALERT",
                    Instant.now(),
                    "price-service",
                    payload
            );

            // when: 서비스가 이벤트를 처리
            notificationService.handlePriceAlert(event);

            // then: sender에 전달된 메시지에 핵심 정보가 포함되어야 한다
            verify(notificationSender).send(org.mockito.ArgumentMatchers.argThat(message ->
                    message.contains("아이폰 16 Pro 256GB") &&   // 상품명 포함
                    message.contains("1,200,000") &&              // 현재 가격 포함 (콤마 포맷)
                    message.contains("1,100,000") &&              // 목표 가격 포함 (콤마 포맷)
                    message.contains("사용자 ID: 1") &&            // 사용자 ID 포함
                    message.contains("https://example.com/iphone16pro")  // 상품 URL 포함
            ));
        }

        /**
         * 가격이 콤마 포맷으로 표시되는지 검증한다.
         *
         * 검증 내용:
         * - 1200000이 "1,200,000"으로 포맷팅되는지 확인
         */
        @Test
        @DisplayName("정상: 가격이 천 단위 콤마 포맷으로 표시된다")
        void handlePriceAlert_priceFormattedWithCommas() {
            // given: 가격이 1200000인 이벤트
            PriceAlertEventPayload payload = new PriceAlertEventPayload(
                    1L,
                    "아이폰 16 Pro 256GB",
                    1200000,
                    1100000,
                    "https://example.com/iphone16pro"
            );
            PriceAlertEvent event = new PriceAlertEvent(
                    "evt-test-001",
                    "PRICE_ALERT",
                    Instant.now(),
                    "price-service",
                    payload
            );

            // when: 서비스가 이벤트를 처리
            notificationService.handlePriceAlert(event);

            // then: 가격이 콤마 포맷으로 표시되어야 한다
            verify(notificationSender).send(org.mockito.ArgumentMatchers.argThat(message ->
                    message.contains("1,200,000원") &&  // 현재 가격 콤마 포맷
                    message.contains("1,100,000원")     // 목표 가격 콤마 포맷
            ));
        }
    }

    /**
     * null 필드가 포함된 이벤트 처리 테스트 그룹.
     * productUrl이 null이거나 빈 문자열인 경우를 대상으로 한다.
     */
    @Nested
    @DisplayName("null/빈 필드 이벤트 처리")
    class NullFieldTests {

        /**
         * productUrl이 null인 이벤트도 정상 처리되는지 검증한다.
         *
         * 검증 내용:
         * - sender.send()가 정상 호출되는지 확인
         * - 메시지에 "상품 URL:"이 포함되지 않는지 확인 (null인 경우 미표시)
         */
        @Test
        @DisplayName("정상: productUrl이 null인 이벤트도 sender를 호출한다")
        void handlePriceAlert_nullProductUrl_callsSender() {
            // given: productUrl이 null인 이벤트
            PriceAlertEventPayload payload = new PriceAlertEventPayload(
                    2L,
                    "갤럭시 S25 Ultra",
                    1300000,
                    1200000,
                    null  // productUrl이 null
            );
            PriceAlertEvent event = new PriceAlertEvent(
                    "evt-test-002",
                    "PRICE_ALERT",
                    Instant.now(),
                    "price-service",
                    payload
            );

            // when: 서비스가 이벤트를 처리
            notificationService.handlePriceAlert(event);

            // then: sender.send()가 정상 호출되어야 한다
            verify(notificationSender).send(anyString());
            // 메시지에 "상품 URL:"이 포함되지 않아야 한다 (null인 경우 미표시)
            verify(notificationSender).send(org.mockito.ArgumentMatchers.argThat(message ->
                    !message.contains("상품 URL:")
            ));
        }

        /**
         * productUrl이 빈 문자열인 이벤트도 정상 처리되는지 검증한다.
         *
         * 검증 내용:
         * - 빈 문자열 productUrl은 null과 동일하게 처리되어 "상품 URL:"이 미표시되어야 한다
         */
        @Test
        @DisplayName("정상: productUrl이 빈 문자열인 이벤트도 URL을 표시하지 않는다")
        void handlePriceAlert_blankProductUrl_noUrlInMessage() {
            // given: productUrl이 빈 문자열인 이벤트
            PriceAlertEventPayload payload = new PriceAlertEventPayload(
                    3L,
                    "맥북 프로 M4",
                    2800000,
                    2500000,
                    ""  // productUrl이 빈 문자열
            );
            PriceAlertEvent event = new PriceAlertEvent(
                    "evt-test-003",
                    "PRICE_ALERT",
                    Instant.now(),
                    "price-service",
                    payload
            );

            // when: 서비스가 이벤트를 처리
            notificationService.handlePriceAlert(event);

            // then: 빈 문자열 productUrl은 "상품 URL:"이 포함되지 않아야 한다
            verify(notificationSender).send(org.mockito.ArgumentMatchers.argThat(message ->
                    !message.contains("상품 URL:")
            ));
        }
    }
}