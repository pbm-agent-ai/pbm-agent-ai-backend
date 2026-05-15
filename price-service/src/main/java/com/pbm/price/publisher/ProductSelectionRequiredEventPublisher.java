package com.pbm.price.publisher;

import com.pbm.price.dto.event.ProductSelectionRequiredEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * product-selection-required 토픽 발행 컴포넌트.
 *
 * 역할: 상품 검증 결과 PRODUCT_SELECTION_REQUIRED일 때
 *       ProductSelectionRequiredEvent를 Kafka product-selection-required 토픽으로 발행한다.
 * 동작: PriceTopicConsumer에서 검증 결과 상품 선택이 필요하면 이 publisher를 호출하여
 *       command-service가 CommandSession 상태를 전환할 수 있도록 이벤트를 전달한다.
 * 연관: PriceTopicConsumer, ProductSelectionRequiredEvent, command-service consumer.
 */
@Slf4j
@Component
public class ProductSelectionRequiredEventPublisher {

    private final KafkaTemplate<String, ProductSelectionRequiredEvent> kafkaTemplate;
    private final String topic;

    public ProductSelectionRequiredEventPublisher(
            @Qualifier("productSelectionRequiredKafkaTemplate")
            KafkaTemplate<String, ProductSelectionRequiredEvent> kafkaTemplate,
            @Value("${app.kafka.topics.product-selection-required-topic}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    /**
     * product-selection-required 토픽으로 상품 선택 필요 이벤트를 발행한다.
     * 키는 commandId 문자열로 설정하여 동일 세션의 이벤트가 동일 파티션에 할당되도록 한다.
     *
     * @param event 발행할 상품 선택 필요 이벤트
     */
    public void publish(ProductSelectionRequiredEvent event) {
        String key = event.payload().commandId();
        log.info("product-selection-required 이벤트 발행 - topic: {}, key: {}, eventId: {}, " +
                        "commandId: {}, missingFields: {}, message: {}",
                topic, key, event.eventId(),
                event.payload().commandId(),
                event.payload().missingFields(),
                event.payload().message());

        kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("product-selection-required 이벤트 발행 실패 - eventId: {}, 에러: {}",
                                event.eventId(), ex.getMessage(), ex);
                    } else {
                        log.info("product-selection-required 이벤트 발행 성공 - topic: {}, partition: {}, offset: {}",
                                result.getRecordMetadata().topic(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
