package com.pbm.price.publisher;

import com.pbm.price.dto.event.PriceAlertEvent;
import com.pbm.price.dto.event.PriceAlertEventPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PriceAlertEventPublisher 단위 테스트
 * KafkaTemplate을 Mock하여 이벤트 발행 로직을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class PriceAlertEventPublisherTest {

    @Mock
    private KafkaTemplate<String, PriceAlertEvent> kafkaTemplate;

    @Captor
    ArgumentCaptor<PriceAlertEvent> eventCaptor;

    private PriceAlertEventPublisher publisher;

    private static final String TOPIC = "price-alert";

    @BeforeEach
    void setUp() {
        publisher = new PriceAlertEventPublisher(kafkaTemplate, TOPIC);
    }

    /**
     * 테스트용 PriceAlertEvent 생성 헬퍼
     */
    private PriceAlertEvent createAlertEvent(Long userId, String productName, int currentPrice, int targetPrice) {
        return new PriceAlertEvent(
                "evt-alert-001",
                "PRICE_ALERT",
                Instant.parse("2026-04-25T12:00:00Z"),
                "price-service",
                new PriceAlertEventPayload(userId, productName, currentPrice, targetPrice, "https://example.com/1", null)
        );
    }

    @Test
    @DisplayName("이벤트 발행 - 올바른 토픽, 키, 이벤트로 Kafka 메시지 전송")
    void publish_sendsEventToCorrectTopicWithCorrectKey() {
        // given
        PriceAlertEvent event = createAlertEvent(1L, "에어팟 프로", 250000, 300000);
        CompletableFuture<SendResult<String, PriceAlertEvent>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(anyString(), anyString(), any(PriceAlertEvent.class))).thenReturn(future);

        // when
        publisher.publish(event);

        // then - 올바른 토픽, 키(userId), 이벤트로 전송되었는지 검증
        verify(kafkaTemplate, times(1)).send(eq(TOPIC), eq("1"), eq(event));
    }

    @Test
    @DisplayName("이벤트 발행 - 다른 userId면 해당 userId 문자열이 키로 사용됨")
    void publish_usesUserIdAsKey() {
        // given
        PriceAlertEvent event = createAlertEvent(42L, "갤럭시 버즈", 120000, 150000);
        CompletableFuture<SendResult<String, PriceAlertEvent>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(anyString(), anyString(), any(PriceAlertEvent.class))).thenReturn(future);

        // when
        publisher.publish(event);

        // then - userId 42가 키로 사용됨
        verify(kafkaTemplate).send(eq(TOPIC), eq("42"), eq(event));
    }

    @Test
    @DisplayName("이벤트 발행 - 이벤트 페이로드가 그대로 전송됨")
    void publish_sendsCompleteEventPayload() {
        // given
        PriceAlertEvent event = createAlertEvent(5L, "맥북 에어", 1200000, 1300000);
        CompletableFuture<SendResult<String, PriceAlertEvent>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(anyString(), anyString(), any(PriceAlertEvent.class))).thenReturn(future);

        // when
        publisher.publish(event);

        // then - 전송된 이벤트의 페이로드 내용 검증
        verify(kafkaTemplate).send(eq(TOPIC), eq("5"), eventCaptor.capture());
        PriceAlertEvent captured = eventCaptor.getValue();
        assertThat(captured.payload().userId()).isEqualTo(5L);
        assertThat(captured.payload().productName()).isEqualTo("맥북 에어");
        assertThat(captured.payload().currentPrice()).isEqualTo(1200000);
        assertThat(captured.payload().targetPrice()).isEqualTo(1300000);
        assertThat(captured.payload().productUrl()).isEqualTo("https://example.com/1");
    }
}