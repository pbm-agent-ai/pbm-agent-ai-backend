package com.pbm.command.publisher;

import com.pbm.command.dto.event.ProductSelectionEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * product-selection 토픽 발행 컴포넌트.
 *
 * 역할: PRODUCT_SELECTION_REQUIRED 상태에서 사용자가 여러 후보 상품을 선택했을 때
 *       ProductSelectionEvent를 Kafka product-selection 토픽으로 발행한다.
 * 동작: CommandExecutionService.handleProductSelection()이 선택 상품 목록을 검증하면
 *       이 publisher를 호출하여 price-service가 실시간 단건 재조회 검증을 시작할 수 있도록
 *       이벤트를 전달한다.
 * 연관: CommandExecutionService, ProductSelectionEvent, price-service consumer.
 */
@Slf4j
@Component
public class ProductSelectionEventPublisher {

    private final KafkaTemplate<String, ProductSelectionEvent> kafkaTemplate;
    private final String topic;

    public ProductSelectionEventPublisher(
            @Qualifier("productSelectionKafkaTemplate")
            KafkaTemplate<String, ProductSelectionEvent> kafkaTemplate,
            @Value("${app.kafka.topics.product-selection-topic}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    /**
     * product-selection 토픽으로 상품 선택 이벤트를 발행한다.
     * 키는 userId 문자열로 설정하여 동일 사용자 이벤트가 동일 파티션에 할당되도록 한다.
     *
     * @param event 발행할 product-selection 이벤트
     */
    public void publish(ProductSelectionEvent event) {
        String key = String.valueOf(event.payload().userId());
        log.info("product-selection 이벤트 발행 - topic: {}, key: {}, eventId: {}, " +
                        "commandId: {}, selectedCount: {}",
                topic, key, event.eventId(),
                event.payload().commandId(),
                event.payload().selectedProducts() != null ? event.payload().selectedProducts().size() : 0);

        kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("product-selection 이벤트 발행 실패 - eventId: {}, 에러: {}",
                                event.eventId(), ex.getMessage(), ex);
                    } else {
                        log.info("product-selection 이벤트 발행 성공 - topic: {}, partition: {}, offset: {}",
                                result.getRecordMetadata().topic(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
