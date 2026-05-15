package com.pbm.price.publisher;

import com.pbm.price.dto.event.PriceValidationResultEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * price-validation-result 토픽 발행 컴포넌트.
 *
 * 역할: 선택 상품 실시간 검증이 끝난 뒤 command-service로 결과를 되돌려준다.
 * 동작: ProductSelectionConsumer가 분류한 triggered / monitoring 결과를 Kafka로 발행한다.
 * 연관: ProductSelectionConsumer, command-service PriceValidationResultConsumer.
 */
@Slf4j
@Component
public class PriceValidationResultEventPublisher {

    private final KafkaTemplate<String, PriceValidationResultEvent> kafkaTemplate;
    private final String topic;

    public PriceValidationResultEventPublisher(
            KafkaTemplate<String, PriceValidationResultEvent> kafkaTemplate,
            @Value("${app.kafka.topics.price-validation-result-topic}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(PriceValidationResultEvent event) {
        String key = event.payload().commandId();
        log.info("price-validation-result 이벤트 발행 - topic: {}, commandId: {}, nextStatus: {}",
                topic, event.payload().commandId(), event.payload().nextStatus());
        kafkaTemplate.send(topic, key, event);
    }
}
