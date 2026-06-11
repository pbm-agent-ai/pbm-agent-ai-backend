package com.pbm.notification.service;

import com.pbm.notification.dto.event.PriceAlertEvent;
import com.pbm.notification.dto.event.PriceAlertEventPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

/**
 * NotificationService 단위 테스트.
 *
 * 역할: NotificationService가 PriceAlertEvent를 받아 포맷팅된 메시지를 생성하고
 *       NotificationDispatcher에 전달하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationDispatcher notificationDispatcher;

    @InjectMocks
    private NotificationService notificationService;

    /**
     * 정상 이벤트 처리 테스트 그룹.
     */
    @Nested
    @DisplayName("정상 이벤트 처리")
    class NormalEventTests {

        @Test
        @DisplayName("정상: 이벤트 처리 시 NotificationDispatcher.dispatch()를 호출한다")
        void handlePriceAlert_callsDispatcher() {
            // given
            PriceAlertEventPayload payload = new PriceAlertEventPayload(
                    1L, "아이폰 16 Pro 256GB", 1200000, 1100000,
                    "https://example.com/iphone16pro", "아이폰 16 프로"
            );
            PriceAlertEvent event = new PriceAlertEvent(
                    "evt-test-001", "PRICE_ALERT", Instant.now(), "price-service", payload
            );

            // when
            notificationService.handlePriceAlert(event);

            // then: dispatcher가 userId=1로 호출되어야 한다
            verify(notificationDispatcher).dispatch(eq(1L), anyString(), anyString());
        }

        @Test
        @DisplayName("정상: 포맷팅된 메시지에 상품명, 가격, URL이 포함된다")
        void handlePriceAlert_messageContainsKeyInfo() {
            // given
            PriceAlertEventPayload payload = new PriceAlertEventPayload(
                    1L, "아이폰 16 Pro 256GB", 1200000, 1100000,
                    "https://example.com/iphone16pro", "아이폰 16 프로"
            );
            PriceAlertEvent event = new PriceAlertEvent(
                    "evt-test-001", "PRICE_ALERT", Instant.now(), "price-service", payload
            );

            // when
            notificationService.handlePriceAlert(event);

            // then
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(notificationDispatcher).dispatch(eq(1L), anyString(), messageCaptor.capture());

            String message = messageCaptor.getValue();
            assertThat(message).contains("아이폰 16 Pro 256GB");
            assertThat(message).contains("1,200,000");
            assertThat(message).contains("1,100,000");
            assertThat(message).contains("https://example.com/iphone16pro");
        }

        @Test
        @DisplayName("정상: 이메일 제목에 상품명이 포함된다")
        void handlePriceAlert_subjectContainsProductName() {
            // given
            PriceAlertEventPayload payload = new PriceAlertEventPayload(
                    1L, "아이폰 16 Pro 256GB", 1200000, 1100000,
                    "https://example.com/iphone16pro", "아이폰 16 프로"
            );
            PriceAlertEvent event = new PriceAlertEvent(
                    "evt-test-001", "PRICE_ALERT", Instant.now(), "price-service", payload
            );

            // when
            notificationService.handlePriceAlert(event);

            // then
            ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
            verify(notificationDispatcher).dispatch(eq(1L), subjectCaptor.capture(), anyString());

            assertThat(subjectCaptor.getValue()).contains("아이폰 16 Pro 256GB");
        }
    }

    /**
     * null/빈 필드 이벤트 처리 테스트 그룹.
     */
    @Nested
    @DisplayName("null/빈 필드 이벤트 처리")
    class NullFieldTests {

        @Test
        @DisplayName("정상: productUrl이 null인 이벤트도 dispatcher를 호출한다")
        void handlePriceAlert_nullProductUrl_callsDispatcher() {
            // given
            PriceAlertEventPayload payload = new PriceAlertEventPayload(
                    2L, "갤럭시 S25 Ultra", 1300000, 1200000, null, "갤럭시"
            );
            PriceAlertEvent event = new PriceAlertEvent(
                    "evt-test-002", "PRICE_ALERT", Instant.now(), "price-service", payload
            );

            // when
            notificationService.handlePriceAlert(event);

            // then: URL이 없어도 정상 발송
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(notificationDispatcher).dispatch(eq(2L), anyString(), messageCaptor.capture());

            assertThat(messageCaptor.getValue()).doesNotContain("상품 바로가기");
        }

        @Test
        @DisplayName("정상: productUrl이 빈 문자열인 이벤트도 URL을 표시하지 않는다")
        void handlePriceAlert_blankProductUrl_noUrlInMessage() {
            // given
            PriceAlertEventPayload payload = new PriceAlertEventPayload(
                    3L, "맥북 프로 M4", 2800000, 2500000, "", "맥북"
            );
            PriceAlertEvent event = new PriceAlertEvent(
                    "evt-test-003", "PRICE_ALERT", Instant.now(), "price-service", payload
            );

            // when
            notificationService.handlePriceAlert(event);

            // then
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(notificationDispatcher).dispatch(eq(3L), anyString(), messageCaptor.capture());

            assertThat(messageCaptor.getValue()).doesNotContain("상품 바로가기");
        }
    }
}
