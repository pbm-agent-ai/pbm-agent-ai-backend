package com.pbm.command.consumer;

import com.pbm.command.dto.event.ProductSelectionRequiredEvent;
import com.pbm.command.dto.event.ProductSelectionRequiredEventPayload;
import com.pbm.command.dto.event.ProductCandidateDto;
import com.pbm.command.service.CommandSessionService;
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
import static org.mockito.Mockito.*;

/**
 * ProductSelectionRequiredConsumer 단위 테스트.
 * CommandSessionService를 Mock하여 Kafka 메시지 수신 시
 * 올바른 파라미터로 updateToProductSelectionRequired이 호출되는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ProductSelectionRequiredConsumerTest {

    @Mock
    private CommandSessionService commandSessionService;

    private ProductSelectionRequiredConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ProductSelectionRequiredConsumer(commandSessionService);
    }

    /**
     * 테스트용 ProductSelectionRequiredEvent 생성 헬퍼 (기본: candidates/targetPrice/intent null).
     */
    private ProductSelectionRequiredEvent createEvent(
            String commandId, List<String> missingFields, String message, String categoryPath) {
        return createEvent(commandId, missingFields, message, categoryPath, null, null, null);
    }

    /**
     * 테스트용 ProductSelectionRequiredEvent 생성 헬퍼 (모든 필드 지정 가능).
     */
    private ProductSelectionRequiredEvent createEvent(
            String commandId, List<String> missingFields, String message, String categoryPath,
            List<ProductCandidateDto> candidates, Integer targetPrice, String intent) {
        return new ProductSelectionRequiredEvent(
                "evt-req-001",
                "PRODUCT_SELECTION_REQUIRED",
                Instant.parse("2026-04-25T12:00:00Z"),
                "price-service",
                new ProductSelectionRequiredEventPayload(
                        commandId, categoryPath, missingFields, message,
                        candidates, targetPrice, intent, null
                )
        );
    }

    @Test
    @DisplayName("이벤트 수신 → CommandSessionService.updateToProductSelectionRequired 호출 (candidates/targetPrice/intent 포함)")
    void consume_callsUpdateToProductSelectionRequired() {
        // given
        List<String> missingFields = List.of("size");
        List<ProductCandidateDto> candidates = List.of(
                new ProductCandidateDto("prod-1", "상품A", "10000", "스토어A", "https://example.com/1", null, "KRW", null, null)
        );
        ProductSelectionRequiredEvent event = createEvent(
                "cmd-uuid-1234", missingFields, "사이즈 정보가 필요합니다", null,
                candidates, 50000, "AUTO_PURCHASE"
        );

        // when
        consumer.consume(event);

        // then - 모든 파라미터가 전달되어야 함
        verify(commandSessionService, times(1)).updateToProductSelectionRequired(
                eq("cmd-uuid-1234"), eq(missingFields), eq("사이즈 정보가 필요합니다"),
                eq(null), eq(candidates), eq(50000), eq("AUTO_PURCHASE")
        );
    }

    @Test
    @DisplayName("이벤트 수신 - candidates/targetPrice/intent가 null이어도 정상 처리")
    void consume_withNullOptionalFields_handlesGracefully() {
        // given
        List<String> missingFields = List.of("searchResultsCount");
        ProductSelectionRequiredEvent event = createEvent(
                "cmd-uuid-5678", missingFields,
                "검색 결과가 명확하지 않습니다", "전자기기 > 스마트폰"
        );

        // when
        consumer.consume(event);

        // then - null 값이 그대로 전달되어야 함
        verify(commandSessionService, times(1)).updateToProductSelectionRequired(
                eq("cmd-uuid-5678"), eq(missingFields),
                eq("검색 결과가 명확하지 않습니다"), eq("전자기기 > 스마트폰"),
                eq(null), eq(null), eq(null)
        );
    }

    @Test
    @DisplayName("이벤트 수신 - 여러 개의 missingFields 전달")
    void consume_withMultipleMissingFields_passesAllFields() {
        // given
        List<String> missingFields = List.of("size", "platform", "color");
        ProductSelectionRequiredEvent event = createEvent(
                "cmd-uuid-9999", missingFields,
                "여러 필드가 누락되었습니다", null
        );

        // when
        consumer.consume(event);

        // then - 모든 missingFields가 전달되어야 함
        ArgumentCaptor<List> listCaptor = ArgumentCaptor.forClass(List.class);
        verify(commandSessionService).updateToProductSelectionRequired(
                eq("cmd-uuid-9999"), listCaptor.capture(), eq("여러 필드가 누락되었습니다"),
                eq(null), eq(null), eq(null), eq(null)
        );
        assertThat(listCaptor.getValue())
                .containsExactly("size", "platform", "color");
    }
}
