package com.pbm.price.publisher;

import com.pbm.price.dto.event.PaymentRequestEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * payment-topic 토픽 발행 컴포넌트.
 *
 * 역할: 목표 가격 충족 시 PaymentRequestEvent를 Kafka payment-topic 토픽으로 발행한다.
 * 동작: PriceTopicConsumer에서 가격 비교 결과가 조건을 만족하면 이 publisher를 호출하여
 *       payment-service가 자동결제를 실행할 수 있도록 결제 요청 이벤트를 전달한다.
 * 연관: PriceTopicConsumer, PaymentRequestEvent, payment-service consumer.
 */
@Slf4j
@Component
public class PaymentRequestEventPublisher {

    private final KafkaTemplate<String, PaymentRequestEvent> kafkaTemplate;
    private final String topic;

    public PaymentRequestEventPublisher(
            KafkaTemplate<String, PaymentRequestEvent> kafkaTemplate,
            @Value("${app.kafka.topics.payment-topic}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    /**
     * payment-topic 토픽으로 결제 요청 이벤트를 발행한다.
     * 키는 userId 문자열로 설정하여 동일 사용자 이벤트가 동일 파티션에 할당되도록 한다.
     *
     * @param event 발행할 결제 요청 이벤트
     */
    public void publish(PaymentRequestEvent event) {
        String key = String.valueOf(event.payload().userId());
        log.info("payment-topic 이벤트 발행 - topic: {}, key: {}, eventId: {}, productName: {}, amount: {}, currency: {}",
                topic, key, event.eventId(), event.payload().productName(),
                event.payload().amount(), event.payload().currency());

        kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("payment-topic 이벤트 발행 실패 - eventId: {}, 에러: {}", event.eventId(), ex.getMessage(), ex);
                    } else {
                        log.info("payment-topic 이벤트 발행 성공 - topic: {}, partition: {}, offset: {}",
                                result.getRecordMetadata().topic(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
