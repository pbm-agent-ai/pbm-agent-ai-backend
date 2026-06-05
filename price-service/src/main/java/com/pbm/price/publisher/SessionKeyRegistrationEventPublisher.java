package com.pbm.price.publisher;

import com.pbm.price.dto.event.SessionKeyRegistrationEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * session-key-registration 토픽 발행 컴포넌트.
 * <p>
 * 역할: AUTO_PURCHASE intent의 신규 모니터링 구독 생성 시 생성된 AI 에이전트 키페어를
 *       payment-service가 블록체인에 등록할 수 있도록 이벤트를 발행한다.
 * 동작: MonitoringSubscriptionService에서 호출되며, session-key-registration 토픽으로
 *       SessionKeyRegistrationEvent를 전송한다.
 * 연관: MonitoringSubscriptionService, payment-service SessionKeyRegistrationConsumer.
 */
@Slf4j
@Component
public class SessionKeyRegistrationEventPublisher {

    private final KafkaTemplate<String, SessionKeyRegistrationEvent> kafkaTemplate;
    private final String topic;

    public SessionKeyRegistrationEventPublisher(
            KafkaTemplate<String, SessionKeyRegistrationEvent> kafkaTemplate,
            @Value("${app.kafka.topics.session-key-registration}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    /**
     * session-key-registration 토픽으로 세션키 등록 요청 이벤트를 발행한다.
     *
     * @param event 발행할 세션키 등록 요청 이벤트
     */
    public void publish(SessionKeyRegistrationEvent event) {
        String key = String.valueOf(event.payload().userId());
        log.info("session-key-registration 이벤트 발행 - topic: {}, subscriptionId: {}, aiAgent: {}",
                topic, event.payload().subscriptionId(), event.payload().aiAgentAddress());

        kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("session-key-registration 이벤트 발행 실패 - eventId: {}, 에러: {}",
                                event.eventId(), ex.getMessage(), ex);
                    } else {
                        log.info("session-key-registration 이벤트 발행 성공 - eventId: {}, partition: {}, offset: {}",
                                event.eventId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
