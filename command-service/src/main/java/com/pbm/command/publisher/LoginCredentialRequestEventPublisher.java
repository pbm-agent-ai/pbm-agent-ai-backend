package com.pbm.command.publisher;

import com.pbm.command.dto.event.LoginCredentialRequestEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 로그인 자격증명 요청 이벤트 발행 컴포넌트.
 */
@Slf4j
@Component
public class LoginCredentialRequestEventPublisher {

    private final KafkaTemplate<String, LoginCredentialRequestEvent> kafkaTemplate;
    private final String topic;

    public LoginCredentialRequestEventPublisher(
            @Qualifier("loginCredentialRequestKafkaTemplate")
            KafkaTemplate<String, LoginCredentialRequestEvent> kafkaTemplate,
            @Value("${app.kafka.topics.login-credential-request-topic}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(LoginCredentialRequestEvent event) {
        String key = String.valueOf(event.payload().userId());
        log.info("로그인 자격증명 요청 이벤트 발행 - topic: {}, key: {}, runId: {}",
                topic, key, event.payload().runId());

        kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("로그인 자격증명 요청 이벤트 발행 실패 - eventId: {}, 에러: {}",
                                event.eventId(), ex.getMessage(), ex);
                    } else {
                        log.info("로그인 자격증명 요청 이벤트 발행 성공 - partition: {}, offset: {}",
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
