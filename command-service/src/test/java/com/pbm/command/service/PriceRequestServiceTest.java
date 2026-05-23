package com.pbm.command.service;

import com.pbm.command.domain.PlatformType;
import com.pbm.command.domain.ProductCategory;
import com.pbm.command.dto.event.ParsedCommandSnapshot;
import com.pbm.command.dto.event.PriceRequestEvent;
import com.pbm.command.dto.request.PriceCheckRequest;
import com.pbm.command.dto.response.ParsedCommand;
import com.pbm.command.dto.response.PriceCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
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
 *       platform과 currency가 페이로드에 포함되는지 확인한다.
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
    @DisplayName("가격 확인 요청 발행 - price-topic으로 platform/currency 포함한 이벤트 전송")
    void publishPriceCheckRequest_sendsKafkaMessage() {
        // given
        PriceCheckRequest request = new PriceCheckRequest(1L, "에어팟 프로", 300000, PlatformType.NAVER, "KRW");
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
        assertThat(event.payload().platform()).isEqualTo("NAVER");
        assertThat(event.payload().currency()).isEqualTo("KRW");
        // PriceCheckRequest 경로에서는 신규 필드가 null이어야 함
        assertThat(event.payload().commandId()).isNull();
        assertThat(event.payload().intent()).isNull();
        assertThat(event.payload().parsedCommandSnapshot()).isNull();
        assertThat(event.payload().productUrls()).isNull();

        assertThat(response.topic()).isEqualTo("price-topic");
        assertThat(response.message()).startsWith("price-topic 발행 성공");
        assertThat(response.eventId()).isNotBlank();
    }

    @Test
    @DisplayName("상품 URL 직접 입력 발행 - productUrls 포함 이벤트 전송")
    void publishProductUrlRequest_sendsKafkaMessage() {
        when(kafkaTemplate.send(anyString(), anyString(), any(PriceRequestEvent.class)))
                .thenReturn(new CompletableFuture<>());

        PriceCheckResponse response = priceRequestService.publishProductUrlRequest(
                1L,
                "AUTO_PURCHASE",
                100000,
                "cmd-url-123",
                java.util.List.of(
                        "https://ko.aliexpress.com/item/1005006782975346.html",
                        "https://ko.aliexpress.com/item/1005010633549414.html"
                ),
                "로지텍 mx master 3s black",
                "ALIEXPRESS"
        );

        ArgumentCaptor<PriceRequestEvent> eventCaptor = ArgumentCaptor.forClass(PriceRequestEvent.class);
        verify(kafkaTemplate).send(eq("price-topic"), eq("1"), eventCaptor.capture());

        PriceRequestEvent event = eventCaptor.getValue();
        assertThat(event.payload().keyword()).isEqualTo("로지텍 mx master 3s black");
        assertThat(event.payload().searchKeyword()).isEqualTo("로지텍 mx master 3s black");
        assertThat(event.payload().platform()).isEqualTo("ALIEXPRESS");
        assertThat(event.payload().intent()).isEqualTo("AUTO_PURCHASE");
        assertThat(event.payload().commandId()).isEqualTo("cmd-url-123");
        assertThat(event.payload().productUrls()).containsExactly(
                "https://ko.aliexpress.com/item/1005006782975346.html",
                "https://ko.aliexpress.com/item/1005010633549414.html"
        );
        assertThat(response.message()).startsWith("price-topic 발행 성공");
    }

    @Test
    @DisplayName("자연어 파싱 결과 기반 발행 - 키워드 조합과 platform/currency 및 스냅샷 포함")
    void publishParsedCommandRequest_buildsKeywordAndPublishesEvent() {
        // given
        String commandId = "cmd-uuid-test-1234";
        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.SHOES,
                "나이키 조던",
                "나이키",
                "조던",
                null,
                "블랙",
                "270",
                List.of(PlatformType.NAVER),
                200000,
                null,
                "KRW"
        );
        when(kafkaTemplate.send(anyString(), anyString(), any(PriceRequestEvent.class)))
                .thenReturn(new CompletableFuture<>());

        // when
        PriceCheckResponse response = priceRequestService.publishParsedCommandRequest(
                1L, "PRICE_CHECK", parsedCommand, commandId);

        // then — platforms=[NAVER] 이므로 1번 발행
        ArgumentCaptor<PriceRequestEvent> eventCaptor = ArgumentCaptor.forClass(PriceRequestEvent.class);
        verify(kafkaTemplate).send(eq("price-topic"), eq("1"), eventCaptor.capture());

        PriceRequestEvent event = eventCaptor.getValue();
        // 키워드는 brand/line이 productName에 포함되므로 productName 중심으로 유지되어야 한다.
        // 기존 필드 검증
        assertThat(event.payload().keyword()).isEqualTo("나이키 조던 블랙 270");
        assertThat(event.payload().targetPrice()).isEqualTo(200000);
        assertThat(event.payload().platform()).isEqualTo("NAVER");
        assertThat(event.payload().currency()).isEqualTo("KRW");
        assertThat(event.payload().userId()).isEqualTo(1L);

        // 신규 필드 검증 — 외부에서 전달된 commandId가 그대로 사용되어야 함
        assertThat(event.payload().commandId()).isEqualTo(commandId);
        assertThat(event.payload().intent()).isEqualTo("PRICE_CHECK");

        ParsedCommandSnapshot snapshot = event.payload().parsedCommandSnapshot();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.productCategory()).isEqualTo("SHOES");
        assertThat(snapshot.productName()).isEqualTo("나이키 조던");
        assertThat(snapshot.brand()).isEqualTo("나이키");
        assertThat(snapshot.line()).isEqualTo("조던");
        assertThat(snapshot.model()).isNull();
        assertThat(snapshot.color()).isEqualTo("블랙");
        assertThat(snapshot.size()).isEqualTo("270");
        assertThat(snapshot.platform()).isEqualTo("NAVER");
        assertThat(snapshot.maxPrice()).isEqualTo(200000);
        assertThat(snapshot.minPrice()).isNull();
        assertThat(snapshot.currency()).isEqualTo("KRW");
        assertThat(snapshot.searchCategoryHint()).isNull();

        assertThat(response.topic()).isEqualTo("price-topic");
        assertThat(response.message()).startsWith("price-topic 발행 성공");
    }

    @Test
    @DisplayName("자연어 파싱 결과 기반 발행 - model만 추가된 키워드 조합")
    void publishParsedCommandRequest_modelOnlyKeyword() {
        // given
        String commandId = "cmd-uuid-model-5678";
        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.ELECTRONICS,
                "아이폰 15 프로",
                "애플",
                "아이폰",
                "256GB",
                null,
                null,
                List.of(PlatformType.NAVER),
                1400000,
                null,
                "KRW"
        );
        when(kafkaTemplate.send(anyString(), anyString(), any(PriceRequestEvent.class)))
                .thenReturn(new CompletableFuture<>());

        // when
        priceRequestService.publishParsedCommandRequest(1L, "PRICE_CHECK", parsedCommand, commandId);

        // then
        ArgumentCaptor<PriceRequestEvent> eventCaptor = ArgumentCaptor.forClass(PriceRequestEvent.class);
        verify(kafkaTemplate).send(eq("price-topic"), eq("1"), eventCaptor.capture());

        PriceRequestEvent event = eventCaptor.getValue();
        assertThat(event.payload().keyword()).isEqualTo("애플 아이폰 15 프로 256GB");
        assertThat(event.payload().commandId()).isEqualTo(commandId);
    }

    @Test
    @DisplayName("자연어 파싱 결과 기반 발행 - productName에 없는 brand와 line은 검색어에 포함한다")
    void publishParsedCommandRequest_includesBrandAndLineWhenNotContainedInProductName() {
        // given
        String commandId = "cmd-uuid-brand-line-3456";
        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.ELECTRONICS,
                "Master 3S",
                "Logitech",
                "MX Master",
                null,
                "black",
                null,
                List.of(PlatformType.ALIEXPRESS),
                100000,
                null,
                "KRW",
                "MOUSE"
        );
        when(kafkaTemplate.send(anyString(), anyString(), any(PriceRequestEvent.class)))
                .thenReturn(new CompletableFuture<>());

        // when
        priceRequestService.publishParsedCommandRequest(1L, "AUTO_PURCHASE", parsedCommand, commandId);

        // then
        ArgumentCaptor<PriceRequestEvent> eventCaptor = ArgumentCaptor.forClass(PriceRequestEvent.class);
        verify(kafkaTemplate).send(eq("price-topic"), eq("1"), eventCaptor.capture());

        PriceRequestEvent event = eventCaptor.getValue();
        assertThat(event.payload().keyword()).isEqualTo("Logitech MX Master Master 3S black");
        assertThat(event.payload().intent()).isEqualTo("AUTO_PURCHASE");
        assertThat(event.payload().parsedCommandSnapshot().searchCategoryHint()).isEqualTo("MOUSE");
    }

    @Test
    @DisplayName("자연어 파싱 결과 기반 발행 - productName에 포함된 model/color는 중복 추가하지 않음")
    void publishParsedCommandRequest_avoidsDuplicatedKeywordParts() {
        // given
        String commandId = "cmd-uuid-dedup-9012";
        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.ELECTRONICS,
                "QCY T13 PRO 블랙",
                "QCY",
                null,
                "T13 PRO",
                "블랙",
                null,
                List.of(PlatformType.ALIEXPRESS),
                50000,
                null,
                "KRW"
        );
        when(kafkaTemplate.send(anyString(), anyString(), any(PriceRequestEvent.class)))
                .thenReturn(new CompletableFuture<>());

        // when
        priceRequestService.publishParsedCommandRequest(1L, "PRICE_CHECK", parsedCommand, commandId);

        // then
        ArgumentCaptor<PriceRequestEvent> eventCaptor = ArgumentCaptor.forClass(PriceRequestEvent.class);
        verify(kafkaTemplate).send(eq("price-topic"), eq("1"), eventCaptor.capture());

        PriceRequestEvent event = eventCaptor.getValue();
        assertThat(event.payload().keyword()).isEqualTo("QCY T13 PRO 블랙");
        // commandId, intent, snapshot 존재 확인
        assertThat(event.payload().commandId()).isEqualTo(commandId);
        assertThat(event.payload().intent()).isEqualTo("PRICE_CHECK");
        assertThat(event.payload().parsedCommandSnapshot()).isNotNull();
    }

    @Test
    @DisplayName("자연어 파싱 결과 기반 발행 - 더 긴 productName이 앞선 짧은 brand와 line을 대체한다")
    void publishParsedCommandRequest_replacesShorterKeywordPartsWithLongerProductName() {
        // given
        String commandId = "cmd-uuid-replace-7890";
        ParsedCommand parsedCommand = new ParsedCommand(
                ProductCategory.ELECTRONICS,
                "로지텍 MX Master 3S",
                "로지텍",
                "MX Master",
                "3S",
                "black",
                null,
                List.of(PlatformType.ALIEXPRESS),
                100000,
                null,
                "KRW"
        );
        when(kafkaTemplate.send(anyString(), anyString(), any(PriceRequestEvent.class)))
                .thenReturn(new CompletableFuture<>());

        // when
        priceRequestService.publishParsedCommandRequest(1L, "AUTO_PURCHASE", parsedCommand, commandId);

        // then
        ArgumentCaptor<PriceRequestEvent> eventCaptor = ArgumentCaptor.forClass(PriceRequestEvent.class);
        verify(kafkaTemplate).send(eq("price-topic"), eq("1"), eventCaptor.capture());

        PriceRequestEvent event = eventCaptor.getValue();
        assertThat(event.payload().keyword()).isEqualTo("로지텍 MX Master 3S black");
    }
}
