package com.pbm.command.publisher;

import com.pbm.command.dto.event.OptionSelectionRequestEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 상품 옵션 선택 요청 이벤트 발행 컴포넌트.
 *
 * 역할: 상품 상세페이지에서 옵션(색상/사이즈)이 필요하나 사용자가 지정하지 않았을 때
 *       notification-service에 텔레그램 옵션 선택 메시지 발송을 요청한다.
 * 연관: AgentRunService, OptionSelectionRequestEvent, notification-service consumer.
 */
@Slf4j
@Component
public class OptionSelectionEventPublisher {

    private final KafkaTemplate<String, OptionSelectionRequestEvent> kafkaTemplate;
    private final String topic;

    public OptionSelectionEventPublisher(
            @Qualifier("optionSelectionRequestKafkaTemplate")
            KafkaTemplate<String, OptionSelectionRequestEvent> kafkaTemplate,
            @Value("${app.kafka.topics.option-selection-request-topic}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    /**
     * option-selection-request 토픽으로 옵션 선택 요청 이벤트를 발행한다.
     *
     * @param event 발행할 옵션 선택 요청 이벤트
     */
    public void publish(OptionSelectionRequestEvent event) {
        String key = String.valueOf(event.payload().userId());
        log.info("옵션 선택 요청 이벤트 발행 - topic: {}, key: {}, runId: {}, groups: {}",
                topic, key, event.payload().runId(), event.payload().optionGroups().size());

        kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("옵션 선택 요청 이벤트 발행 실패 - eventId: {}, 에러: {}",
                                event.eventId(), ex.getMessage(), ex);
                    } else {
                        log.info("옵션 선택 요청 이벤트 발행 성공 - partition: {}, offset: {}",
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
