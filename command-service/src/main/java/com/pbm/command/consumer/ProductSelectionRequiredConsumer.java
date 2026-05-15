package com.pbm.command.consumer;

import com.pbm.command.dto.event.ProductSelectionRequiredEvent;
import com.pbm.command.service.CommandSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * product-selection-required 토픽 메시지 소비 컴포넌트.
 *
 * 역할: price-service가 검색 결과 후보 상품 목록을 전달했을 때
 *       해당 후보를 CommandSession에 저장하고 상태를 전환한다.
 * 동작:
 *   1. product-selection-required 토픽에서 ProductSelectionRequiredEvent 수신
 *   2. payload의 commandId, candidates, targetPrice, intent 등의 값을 추출
 *   3. CommandSessionService.updateToProductSelectionRequired() 호출
 * 연관: ProductSelectionRequiredEvent, CommandSessionService,
 *       price-service ProductSelectionRequiredEventPublisher.
 */
@Slf4j
@Component
public class ProductSelectionRequiredConsumer {

    private final CommandSessionService commandSessionService;

    public ProductSelectionRequiredConsumer(CommandSessionService commandSessionService) {
        this.commandSessionService = commandSessionService;
    }

    /**
     * product-selection-required 토픽 메시지 수신 및 CommandSession 상태 전환 처리.
     * <p>
     * payload에 포함된 candidates(후보 상품 목록), targetPrice(목표 가격),
     * intent(사용자 의도)를 함께 전달하여 세션에 저장한다.
     * 현재 흐름에서는 missingFields는 대부분 비어 있고,
     * message는 프론트에 보여줄 안내 문구로 사용된다.
     *
     * @param event 수신한 product-selection-required 이벤트
     */
    @KafkaListener(
            topics = "${app.kafka.topics.product-selection-required-topic}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "productSelectionRequiredContainerFactory"
    )
    public void consume(ProductSelectionRequiredEvent event) {
        log.info("후보 선택 요청 메시지 수신 - eventId: {}, commandId: {}, candidateCount: {}, message: {}",
                event.eventId(), event.payload().commandId(),
                event.payload().candidates() != null ? event.payload().candidates().size() : 0,
                event.payload().message());

        String commandId = event.payload().commandId();
        var missingFields = event.payload().missingFields();
        String message = event.payload().message();
        String categoryPath = event.payload().categoryPath();
        var candidates = event.payload().candidates();
        Integer targetPrice = event.payload().targetPrice();
        String intent = event.payload().intent();

        commandSessionService.updateToProductSelectionRequired(
                commandId, missingFields, message, categoryPath,
                candidates, targetPrice, intent
        );

        log.info("product-selection-required 처리 완료 - commandId: {}, status: PRODUCT_SELECTION_REQUIRED" +
                        ", candidates: {}, targetPrice: {}, intent: {}",
                commandId,
                candidates != null ? candidates.size() : 0,
                targetPrice, intent);
    }
}
