package com.pbm.price.publisher;

import com.pbm.price.dto.event.PriceAlertEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * price-alert 토픽 발행 컴포넌트.
 *
 * 역할: 목표 가격 충족 시 PriceAlertEvent를 Kafka price-alert 토픽으로 발행한다.
 * 동작: PriceTopicConsumer에서 가격 비교 결과가 조건을 만족하면 이 publisher를 호출하여
 *       notification-service 등 하위 소비자가 알림을 처리할 수 있도록 한다.
 * 연관: PriceTopicConsumer, PriceAlertEvent.
 */
@Slf4j
@Component
public class PriceAlertEventPublisher {

    private final KafkaTemplate<String, PriceAlertEvent> kafkaTemplate;
    private final String topic;

    public PriceAlertEventPublisher(
            KafkaTemplate<String, PriceAlertEvent> kafkaTemplate,
            @Value("${app.kafka.topics.price-alert}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    /**
     * price-alert 토픽으로 가격 알림 이벤트를 발행한다.
     * 키는 userId 문자열로 설정하여 동일 사용자 이벤트가 동일 파티션에 할당되도록 한다.
     *
     * @param event 발행할 가격 알림 이벤트
     */
    public void publish(PriceAlertEvent event) {
        String key = String.valueOf(event.payload().userId());
        log.info("price-alert 이벤트 발행 - topic: {}, key: {}, eventId: {}, productName: {}, currentPrice: {}, targetPrice: {}",
                topic, key, event.eventId(), event.payload().productName(),
                event.payload().currentPrice(), event.payload().targetPrice());

        kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("price-alert 이벤트 발행 실패 - eventId: {}, 에러: {}", event.eventId(), ex.getMessage(), ex);
                    } else {
                        log.info("price-alert 이벤트 발행 성공 - topic: {}, partition: {}, offset: {}",
                                result.getRecordMetadata().topic(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}