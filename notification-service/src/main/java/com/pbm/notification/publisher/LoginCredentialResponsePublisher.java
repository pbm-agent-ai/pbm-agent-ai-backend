package com.pbm.notification.publisher;

import com.pbm.notification.dto.event.LoginCredentialResponseEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 로그인 자격증명 응답 이벤트 발행 컴포넌트.
 */
@Slf4j
@Component
public class LoginCredentialResponsePublisher {

    private final KafkaTemplate<String, LoginCredentialResponseEvent> kafkaTemplate;
    private final String topic;

    public LoginCredentialResponsePublisher(
            KafkaTemplate<String, LoginCredentialResponseEvent> kafkaTemplate,
            @Value("${app.kafka.topics.login-credential-response}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(LoginCredentialResponseEvent event) {
        String key = String.valueOf(event.payload().userId());
        log.info("로그인 자격증명 응답 이벤트 발행 - topic: {}, runId: {}, username: {}, passwordLength: {}",
                topic,
                event.payload().runId(),
                maskUsername(event.payload().username()),
                event.payload().password() == null ? 0 : event.payload().password().length());

        kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("로그인 자격증명 응답 발행 실패 - eventId: {}, 에러: {}",
                                event.eventId(), ex.getMessage(), ex);
                    } else {
                        log.info("로그인 자격증명 응답 발행 성공 - partition: {}, offset: {}",
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }

    private String maskUsername(String username) {
        if (username == null || username.isBlank()) {
            return "(empty)";
        }
        if (username.length() <= 2) {
            return username.charAt(0) + "*";
        }
        return username.substring(0, 2) + "***";
    }
}
