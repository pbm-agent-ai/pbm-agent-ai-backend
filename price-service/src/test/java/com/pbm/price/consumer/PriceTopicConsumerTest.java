package com.pbm.price.consumer;

import com.pbm.price.dto.event.PriceAlertEvent;
import com.pbm.price.dto.event.PriceRequestEvent;
import com.pbm.price.dto.event.PriceRequestEventPayload;
import com.pbm.price.dto.response.SearchResponse;
import com.pbm.price.publisher.PriceAlertEventPublisher;
import com.pbm.price.service.NaverShoppingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * PriceTopicConsumer 단위 테스트
 * NaverShoppingService와 PriceAlertEventPublisher를 Mock하여
 * 가격 비교 로직과 이벤트 발행 조건을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class PriceTopicConsumerTest {

    @Mock
    private NaverShoppingService naverShoppingService;

    @Mock
    private PriceAlertEventPublisher priceAlertEventPublisher;

    private PriceTopicConsumer priceTopicConsumer;

    @BeforeEach
    void setUp() {
        priceTopicConsumer = new PriceTopicConsumer(naverShoppingService, priceAlertEventPublisher);
    }

    /**
     * 테스트용 PriceRequestEvent 생성 헬퍼
     */
    private PriceRequestEvent createRequestEvent(Long userId, String keyword, Integer targetPrice) {
        return new PriceRequestEvent(
                "evt-001",
                "PRICE_CHECK_REQUEST",
                Instant.now(),
                "command-service",
                new PriceRequestEventPayload(userId, keyword, targetPrice)
        );
    }

    @Test
    @DisplayName("가격 비교 - 현재가가 목표가 이하이면 price-alert 이벤트 발행")
    void consume_targetMet_publishesAlertEvent() {
        // given - 현재가 250000 ≤ 목표가 300000
        PriceRequestEvent event = createRequestEvent(1L, "에어팟 프로", 300000);
        List<SearchResponse> results = List.of(
                new SearchResponse("에어팟 프로", "250000", "350000", "애플스토어", "https://example.com/1")
        );
        when(naverShoppingService.searchProducts("에어팟 프로", 1)).thenReturn(results);

        // when
        priceTopicConsumer.consume(event);

        // then - publisher가 호출되었는지 검증
        ArgumentCaptor<PriceAlertEvent> captor = ArgumentCaptor.forClass(PriceAlertEvent.class);
        verify(priceAlertEventPublisher, times(1)).publish(captor.capture());

        PriceAlertEvent alertEvent = captor.getValue();
        assertThat(alertEvent.eventType()).isEqualTo("PRICE_ALERT");
        assertThat(alertEvent.producer()).isEqualTo("price-service");
        assertThat(alertEvent.payload().userId()).isEqualTo(1L);
        assertThat(alertEvent.payload().productName()).isEqualTo("에어팟 프로");
        assertThat(alertEvent.payload().currentPrice()).isEqualTo(250000);
        assertThat(alertEvent.payload().targetPrice()).isEqualTo(300000);
        assertThat(alertEvent.payload().productUrl()).isEqualTo("https://example.com/1");
    }

    @Test
    @DisplayName("가격 비교 - 현재가가 목표가와 정확히 같아도 price-alert 이벤트 발행")
    void consume_currentPriceEqualsTarget_publishesAlertEvent() {
        // given - 현재가 300000 == 목표가 300000
        PriceRequestEvent event = createRequestEvent(2L, "갤럭시 버즈", 300000);
        List<SearchResponse> results = List.of(
                new SearchResponse("갤럭시 버즈", "300000", "350000", "삼성스토어", "https://example.com/2")
        );
        when(naverShoppingService.searchProducts("갤럭시 버즈", 1)).thenReturn(results);

        // when
        priceTopicConsumer.consume(event);

        // then
        verify(priceAlertEventPublisher, times(1)).publish(any(PriceAlertEvent.class));
    }

    @Test
    @DisplayName("가격 비교 - 현재가가 목표가 초과 시 price-alert 이벤트 발행하지 않음")
    void consume_targetNotMet_doesNotPublishAlertEvent() {
        // given - 현재가 350000 > 목표가 300000
        PriceRequestEvent event = createRequestEvent(1L, "에어팟 프로", 300000);
        List<SearchResponse> results = List.of(
                new SearchResponse("에어팟 프로", "350000", "400000", "애플스토어", "https://example.com/1")
        );
        when(naverShoppingService.searchProducts("에어팟 프로", 1)).thenReturn(results);

        // when
        priceTopicConsumer.consume(event);

        // then - publisher가 호출되지 않아야 함
        verify(priceAlertEventPublisher, never()).publish(any(PriceAlertEvent.class));
    }

    @Test
    @DisplayName("가격 비교 - 검색 결과가 없으면 price-alert 이벤트 발행하지 않음")
    void consume_noResults_doesNotPublishAlertEvent() {
        // given - 검색 결과 없음
        PriceRequestEvent event = createRequestEvent(1L, "존재하지않는상품", 100000);
        when(naverShoppingService.searchProducts("존재하지않는상품", 1)).thenReturn(List.of());

        // when
        priceTopicConsumer.consume(event);

        // then - publisher가 호출되지 않아야 함
        verify(priceAlertEventPublisher, never()).publish(any(PriceAlertEvent.class));
    }

    @Test
    @DisplayName("가격 비교 - lprice가 빈 문자열이면 조건 미충족으로 처리하고 발행하지 않음")
    void consume_emptyLprice_doesNotPublishAlertEvent() {
        // given - lprice가 빈 문자열 (파싱 실패 → Integer.MAX_VALUE)
        PriceRequestEvent event = createRequestEvent(1L, "이상한상품", 100000);
        List<SearchResponse> results = List.of(
                new SearchResponse("이상한상품", "", "0", "테스트몰", "https://example.com/3")
        );
        when(naverShoppingService.searchProducts("이상한상품", 1)).thenReturn(results);

        // when
        priceTopicConsumer.consume(event);

        // then - 빈 문자열은 파싱 실패 → 최대값 → 조건 미충족
        verify(priceAlertEventPublisher, never()).publish(any(PriceAlertEvent.class));
    }

    @Test
    @DisplayName("가격 비교 - lprice가 숫자가 아닌 문자열이면 조건 미충족으로 처리")
    void consume_invalidLprice_doesNotPublishAlertEvent() {
        // given - lprice가 숫자가 아닌 문자열
        PriceRequestEvent event = createRequestEvent(1L, "비정상상품", 100000);
        List<SearchResponse> results = List.of(
                new SearchResponse("비정상상품", "N/A", "0", "테스트몰", "https://example.com/4")
        );
        when(naverShoppingService.searchProducts("비정상상품", 1)).thenReturn(results);

        // when
        priceTopicConsumer.consume(event);

        // then - 파싱 실패 → 최대값 → 조건 미충족
        verify(priceAlertEventPublisher, never()).publish(any(PriceAlertEvent.class));
    }
}