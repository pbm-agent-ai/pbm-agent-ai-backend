package com.pbm.command.publisher;

import com.pbm.command.dto.event.CheckoutPaymentEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * payment-topic 결제 요청 이벤트 발행 컴포넌트.
 *
 * 역할: 브라우저 자동화가 결제 페이지에 도달했을 때 payment-topic으로
 *       PBM 토큰 차감 요청 이벤트를 발행한다.
 * 동작: payment-service의 PaymentRequestConsumer가 동일 토픽을 소비하여
 *       실제 블록체인 토큰 차감을 수행한다.
 * 연관: AgentRunService, CheckoutPaymentEvent, payment-service consumer.
 */
@Slf4j
@Component
public class CheckoutPaymentEventPublisher {

    private final KafkaTemplate<String, CheckoutPaymentEvent> kafkaTemplate;
    private final String topic;

    public CheckoutPaymentEventPublisher(
            @Qualifier("checkoutPaymentKafkaTemplate")
            KafkaTemplate<String, CheckoutPaymentEvent> kafkaTemplate,
            @Value("${app.kafka.topics.payment-topic}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    /**
     * payment-topic으로 결제 요청 이벤트를 발행한다.
     * 키는 userId 문자열로 설정하여 동일 사용자 이벤트가 동일 파티션에 할당되도록 한다.
     *
     * @param event 발행할 결제 요청 이벤트
     */
    public void publish(CheckoutPaymentEvent event) {
        String key = String.valueOf(event.payload().userId());
        log.info("결제 페이지 도달 → payment-topic 이벤트 발행 - topic: {}, key: {}, eventId: {}, amount: {}, product: {}",
                topic, key, event.eventId(),
                event.payload().amount(),
                event.payload().productName());

        kafkaTemplate.send(topic, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("결제 이벤트 발행 실패 - eventId: {}, 에러: {}",
                                event.eventId(), ex.getMessage(), ex);
                    } else {
                        log.info("결제 이벤트 발행 성공 - topic: {}, partition: {}, offset: {}",
                                result.getRecordMetadata().topic(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
