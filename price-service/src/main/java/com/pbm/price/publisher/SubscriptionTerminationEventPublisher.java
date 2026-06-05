package com.pbm.price.publisher;

import com.pbm.price.dto.event.SubscriptionTerminationEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * subscription-termination 토픽 발행 컴포넌트.
 * <p>
 * 역할: 구독 취소(CANCELLED) 또는 기간 만료(EXPIRED) 시 payment-service가
 *       세션키를 폐기/만료 처리할 수 있도록 이벤트를 발행한다.
 * 연관: MonitoringSubscriptionService, SubscriptionMonitoringScheduler,
 *       payment-service SubscriptionTerminationConsumer.
 */
@Slf4j
@Component
public class SubscriptionTerminationEventPublisher {

    private final KafkaTemplate<String, SubscriptionTerminationEvent> kafkaTemplate;
    private final String topic;

    public SubscriptionTerminationEventPublisher(
            KafkaTemplate<String, SubscriptionTerminationEvent> kafkaTemplate,
            @Value("${app.kafka.topics.subscription-termination}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(SubscriptionTerminationEvent event) {
        String key = String.valueOf(event.payload().subscriptionId());
        log.info("subscription-termination 이벤트 발행 - topic: {}, subscriptionId: {}, reason: {}",
                topic, event.payload().subscriptionId(), event.payload().reason());

        kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("subscription-termination 이벤트 발행 실패 - eventId: {}, 에러: {}",
                                event.eventId(), ex.getMessage(), ex);
                    } else {
                        log.info("subscription-termination 이벤트 발행 성공 - eventId: {}, partition: {}, offset: {}",
                                event.eventId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
