package com.pbm.payment.service;

import com.pbm.payment.dto.event.PaymentRequestEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * payment-topic 토픽에서 결제 요청 이벤트를 수신하는 Kafka Consumer.
 *
 * 역할: price-service가 자동결제 조건 충족을 판단하고 발행한
 *       결제 요청 이벤트를 수신하여 PaymentService의 처리 흐름을 시작한다.
 * 동작: @KafkaListener로 지정된 토픽(payment-topic)의 메시지를
 *       PaymentRequestEvent로 역직렬화한 뒤, PaymentService.processPaymentRequest()에
 *       위임한다. 예외 발생 시 로그를 남기고 메시지를 소비 완료 처리하여
 *       무한 재시도를 방지한다 (추후 DLQ 연동 예정).
 * 연관: PaymentRequestEvent, PaymentService.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRequestConsumer {

    private final PaymentService paymentService;

    /**
     * payment-topic 토픽에서 결제 요청 이벤트를 수신한다.
     *
     * 처리 흐름:
     * 1. Kafka로부터 PaymentRequestEvent를 수신하고 역직렬화한다.
     * 2. 수신한 이벤트의 주요 정보(eventId, userId, 상품명)를 로그로 기록한다.
     * 3. PaymentService.processPaymentRequest(event)를 호출하여
     *    결제 엔티티 생성 → 블록체인 처리 → 상태 갱신 → 결과 발행까지의
     *    전체 플로우를 실행한다.
     * 4. 예외 발생 시 상세 로그를 남기고 정상 반환한다.
     *    (오프셋을 커밋하여 동일 메시지의 무한 재처리를 방지한다)
     *
     * 주의: 예외 발생 시에도 오프셋이 커밋되므로, 실패한 결제 요청이
     *       유실될 수 있다. 추후 Dead Letter Queue(DLQ) 연동을 통해
     *       실패 메시지를 별도 보관하고 재처리할 수 있도록 개선할 예정이다.
     *
     * @param event price-service가 발행한 결제 요청 이벤트
     */
    @KafkaListener(
            // topics = "${app.kafka.topics.payment-topic}" 이건 application.yml에서 값을 가져오는 문법이다.
            topics = "${app.kafka.topics.payment-topic}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void consume(PaymentRequestEvent event) {
        log.info("결제 요청 이벤트 수신: eventId={}, userId={}, product={}",
                event.eventId(),
                event.payload().userId(),
                event.payload().productName());

        try {
            // Kafka에서 메시지가 오면 이 메서드가 자동으로 실행된다.
            paymentService.processPaymentRequest(event);
            log.info("결제 요청 처리 완료: eventId={}", event.eventId());
        } catch (Exception e) {
            // 예외 발생 시 로그를 남기고 정상 종료하여 무한 재시도를 방지한다.
            // 추후 DLQ 연동 시 실패 메시지를 DLQ로 전송하는 로직이 추가될 예정이다.
            log.error("결제 요청 처리 중 예외 발생: eventId={}, userId={}, 원인={}",
                    event.eventId(), event.payload().userId(), e.getMessage(), e);
        }
    }
}
