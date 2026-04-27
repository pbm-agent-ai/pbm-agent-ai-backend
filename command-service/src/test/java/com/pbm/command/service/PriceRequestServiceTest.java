package com.pbm.command.service;

import com.pbm.command.dto.event.PriceRequestEvent;
import com.pbm.command.dto.request.PriceCheckRequest;
import com.pbm.command.dto.response.PriceCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PriceRequestService 단위 테스트.
 *
 * 역할: 가격 확인 요청이 Kafka price-topic으로 올바르게 발행되는지 검증한다.
 * 동작: KafkaTemplate을 Mock으로 대체하여 topic, key, event payload를 확인한다.
 * 연관: PriceRequestService, PriceRequestEvent.
 */
@ExtendWith(MockitoExtension.class)
class PriceRequestServiceTest {

    @Mock
    private KafkaTemplate<String, PriceRequestEvent> kafkaTemplate;

    private PriceRequestService priceRequestService;

    @BeforeEach
    void setUp() {
        priceRequestService = new PriceRequestService(kafkaTemplate, "price-topic");
    }

    @Test
    @DisplayName("가격 확인 요청 발행 - price-topic으로 올바른 이벤트 전송")
    void publishPriceCheckRequest_sendsKafkaMessage() {
        // given
        PriceCheckRequest request = new PriceCheckRequest(1L, "에어팟 프로", 300000);
        when(kafkaTemplate.send(anyString(), anyString(), any(PriceRequestEvent.class)))
                .thenReturn(new CompletableFuture<>());

        // when
        PriceCheckResponse response = priceRequestService.publishPriceCheckRequest(request);

        // then
        ArgumentCaptor<PriceRequestEvent> eventCaptor = ArgumentCaptor.forClass(PriceRequestEvent.class);
        verify(kafkaTemplate).send(eq("price-topic"), eq("1"), eventCaptor.capture());

        PriceRequestEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo("PRICE_CHECK_REQUEST");
        assertThat(event.producer()).isEqualTo("command-service");
        assertThat(event.payload().userId()).isEqualTo(1L);
        assertThat(event.payload().keyword()).isEqualTo("에어팟 프로");
        assertThat(event.payload().targetPrice()).isEqualTo(300000);

        assertThat(response.topic()).isEqualTo("price-topic");
        assertThat(response.message()).isEqualTo("price-topic 발행 성공");
        assertThat(response.eventId()).isNotBlank();
    }
}
