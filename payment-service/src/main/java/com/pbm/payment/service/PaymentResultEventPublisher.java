package com.pbm.payment.service;

import com.pbm.payment.domain.Payment;
import com.pbm.payment.domain.PaymentStatus;
import com.pbm.payment.dto.event.PaymentResultEvent;
import com.pbm.payment.dto.event.PaymentResultEventPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * 결제 결과 이벤트를 payment-result 토픽으로 발행하는 컴포넌트.
 *
 * 역할: PaymentService에서 결제 처리가 완료되면,
 *       성공/실패 여부에 따라 적절한 PaymentResultEvent를 생성하여
 *       Kafka payment-result 토픽으로 발행한다.
 * 동작: publish() 메서드는 Payment 엔티티의 최종 상태를 확인하여
 *       SUCCESS → PAYMENT_COMPLETED,
 *       FAILED  → PAYMENT_FAILED
 *       타입으로 이벤트를 구성하고 KafkaTemplate을 통해 전송한다.
 *       PENDING 상태이면 아직 최종 결과가 아니므로 발행하지 않는다.
 *       메시지 키로 userId 문자열을 사용하여 같은 사용자의 이벤트가
 *       순서대로 처리되도록 보장한다.
 * 연관: PaymentService, PaymentResultEvent, KafkaTemplate.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentResultEventPublisher {

    private final KafkaTemplate<String, PaymentResultEvent> kafkaTemplate;

    @Value("${app.kafka.topics.payment-result}")
    private String paymentResultTopic;

    /** Kafka 메시지 헤더에 기록할 producer 식별자 */
    private static final String PRODUCER_NAME = "payment-service";

    /**
     * 결제 최종 결과를 payment-result 토픽으로 발행한다.
     *
     * 처리 흐름:
     * 1. Payment의 status를 확인하여 이벤트 타입을 결정한다.
     *    - SUCCESS → "PAYMENT_COMPLETED"
     *    - FAILED  → "PAYMENT_FAILED"
     *    - PENDING → 발행하지 않음 (아직 최종 결과가 아니므로)
     * 2. PaymentResultEventPayload를 구성한다.
     * 3. PaymentResultEvent Envelope을 생성한다.
     * 4. userId를 문자열 키로 사용하여 Kafka에 발행한다.
     *
     * @param payment            최종 상태(SUCCESS 또는 FAILED)로 갱신된 결제 엔티티
     * @param userFacingMessage  사용자에게 전달할 알림 메시지
     *                           (예: "결제가 완료되었습니다", "잔액 부족으로 결제가 실패했습니다")
     */
    public void publish(Payment payment, String userFacingMessage) {
        PaymentStatus status = payment.getStatus();

        // 최종 상태에 따라 이벤트 타입 결정
        String eventType;
        if (status == PaymentStatus.SUCCESS) {
            eventType = "PAYMENT_COMPLETED";
        } else if (status == PaymentStatus.FAILED) {
            eventType = "PAYMENT_FAILED";
        } else {
            // PENDING 상태에서는 아직 최종 결과가 아니므로 발행하지 않는다.
            log.debug("PENDING 상태는 최종 결과가 아니므로 이벤트를 발행하지 않습니다: paymentId={}",
                    payment.getPaymentId());
            return;
        }

        // 결제 결과 payload 구성
        PaymentResultEventPayload payload = new PaymentResultEventPayload(
                payment.getUserId(),
                payment.getPaymentId(),
                status.name(),
                payment.getTransactionHash(),
                userFacingMessage
        );

        // 이벤트 Envelope 구성 (eventId는 서비스 내부에서 새로 생성)
        PaymentResultEvent event = new PaymentResultEvent(
                UUID.randomUUID().toString(),
                eventType,
                Instant.now(),
                PRODUCER_NAME,
                payload
        );

        // userId를 메시지 키로 사용하여 발행 (같은 사용자의 이벤트 순서 보장)
        String messageKey = String.valueOf(payment.getUserId());
        kafkaTemplate.send(paymentResultTopic, messageKey, event);

        log.info("결제 결과 이벤트 발행 완료: topic={}, key={}, eventType={}, paymentId={}",
                paymentResultTopic, messageKey, eventType, payment.getPaymentId());
    }
}
