package com.pbm.price.publisher;

import com.pbm.price.dto.event.ProductSelectionRequiredEvent;
import com.pbm.price.dto.event.ProductSelectionRequiredEventPayload;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ProductSelectionRequiredEventPublisher 단위 테스트.
 * KafkaTemplate을 Mock하여 이벤트 발행 로직을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ProductSelectionRequiredEventPublisherTest {

    @Mock
    private KafkaTemplate<String, ProductSelectionRequiredEvent> kafkaTemplate;

    @Captor
    ArgumentCaptor<ProductSelectionRequiredEvent> eventCaptor;

    private ProductSelectionRequiredEventPublisher publisher;

    private static final String TOPIC = "product-selection-required";

    @BeforeEach
    void setUp() {
        publisher = new ProductSelectionRequiredEventPublisher(kafkaTemplate, TOPIC);
    }

    /**
     * 테스트용 ProductSelectionRequiredEvent 생성 헬퍼.
     */
    private ProductSelectionRequiredEvent createSelectionRequiredEvent(
            String commandId, List<String> missingFields, String message) {
        return new ProductSelectionRequiredEvent(
                "evt-selreq-001",
                "PRODUCT_SELECTION_REQUIRED",
                Instant.parse("2026-04-25T12:00:00Z"),
                "price-service",
                new ProductSelectionRequiredEventPayload(
                        commandId, null, missingFields, message,
                        null, null, null, null
                )
        );
    }

    @Test
    @DisplayName("이벤트 발행 - 올바른 토픽, 키, 이벤트로 Kafka 메시지 전송")
    void publish_sendsEventToCorrectTopicWithCorrectKey() {
        // given
        ProductSelectionRequiredEvent event = createSelectionRequiredEvent(
                "cmd-uuid-1234", List.of("size"), "사이즈 정보가 필요합니다"
        );
        CompletableFuture<SendResult<String, ProductSelectionRequiredEvent>> future =
                new CompletableFuture<>();
        when(kafkaTemplate.send(anyString(), anyString(), any(ProductSelectionRequiredEvent.class)))
                .thenReturn(future);

        // when
        publisher.publish(event);

        // then - 올바른 토픽, 키(commandId), 이벤트로 전송되었는지 검증
        verify(kafkaTemplate, times(1)).send(eq(TOPIC), eq("cmd-uuid-1234"), eq(event));
    }

    @Test
    @DisplayName("이벤트 발행 - commandId가 키로 사용됨")
    void publish_usesCommandIdAsKey() {
        // given
        ProductSelectionRequiredEvent event = createSelectionRequiredEvent(
                "cmd-different-5678", List.of("platform", "size"), "플랫폼과 사이즈를 알려주세요"
        );
        CompletableFuture<SendResult<String, ProductSelectionRequiredEvent>> future =
                new CompletableFuture<>();
        when(kafkaTemplate.send(anyString(), anyString(), any(ProductSelectionRequiredEvent.class)))
                .thenReturn(future);

        // when
        publisher.publish(event);

        // then - commandId "cmd-different-5678"이 키로 사용됨
        verify(kafkaTemplate).send(eq(TOPIC), eq("cmd-different-5678"), eq(event));
    }

    @Test
    @DisplayName("이벤트 발행 - 이벤트 페이로드가 그대로 전송됨")
    void publish_sendsCompleteEventPayload() {
        // given
        ProductSelectionRequiredEvent event = createSelectionRequiredEvent(
                "cmd-complete-9999", List.of("size", "searchResultsCount"), "검색 결과가 명확하지 않습니다"
        );
        CompletableFuture<SendResult<String, ProductSelectionRequiredEvent>> future =
                new CompletableFuture<>();
        when(kafkaTemplate.send(anyString(), anyString(), any(ProductSelectionRequiredEvent.class)))
                .thenReturn(future);

        // when
        publisher.publish(event);

        // then - 전송된 이벤트의 페이로드 내용 검증
        verify(kafkaTemplate).send(eq(TOPIC), eq("cmd-complete-9999"), eventCaptor.capture());
        ProductSelectionRequiredEvent captured = eventCaptor.getValue();
        assertThat(captured.payload().commandId()).isEqualTo("cmd-complete-9999");
        assertThat(captured.payload().missingFields()).containsExactly("size", "searchResultsCount");
        assertThat(captured.payload().message()).isEqualTo("검색 결과가 명확하지 않습니다");
    }
}
