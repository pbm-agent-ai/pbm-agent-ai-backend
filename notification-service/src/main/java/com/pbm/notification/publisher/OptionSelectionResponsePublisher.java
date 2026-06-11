package com.pbm.notification.publisher;

import com.pbm.notification.dto.event.OptionSelectionResponseEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 텔레그램 옵션 선택 응답 이벤트 발행 컴포넌트.
 *
 * 역할: 사용자가 텔레그램으로 옵션을 선택하면 command-service에 결과를 전달한다.
 * 연관: TelegramOptionSelectionHandler, OptionSelectionResponseEvent.
 */
@Slf4j
@Component
public class OptionSelectionResponsePublisher {

    private final KafkaTemplate<String, OptionSelectionResponseEvent> kafkaTemplate;
    private final String topic;

    public OptionSelectionResponsePublisher(
            KafkaTemplate<String, OptionSelectionResponseEvent> kafkaTemplate,
            @Value("${app.kafka.topics.option-selection-response}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(OptionSelectionResponseEvent event) {
        String key = String.valueOf(event.payload().userId());
        log.info("옵션 선택 응답 이벤트 발행 - topic: {}, runId: {}, selectedValue: {}",
                topic, event.payload().runId(), event.payload().selectedValue());

        kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("옵션 선택 응답 발행 실패 - eventId: {}, 에러: {}",
                                event.eventId(), ex.getMessage(), ex);
                    } else {
                        log.info("옵션 선택 응답 발행 성공 - partition: {}, offset: {}",
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
