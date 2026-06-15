package com.pbm.notification.service;

import com.pbm.notification.dto.event.PaymentResultEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * payment-result 토픽을 소비하는 Kafka 컨슈머 서비스.
 *
 * 역할: payment-service가 발행한 결제 결과 이벤트를 Kafka에서 수신하여
 *       NotificationService에 전달한다.
 * 동작:
 *   1. @KafkaListener가 "payment-result" 토픽의 메시지를 자동으로 폴링한다.
 *   2. properties 설정으로 spring.json.value.default.type을 PaymentResultEvent로 지정하여
 *      JSON 역직렬화가 올바르게 동작하도록 보장한다.
 *   3. 수신한 이벤트를 NotificationService.handlePaymentResult()에 위임한다.
 * 연관: PaymentResultEvent, NotificationService.
 *
 * Kafka 컨슈머 동작 흐름:
 * 1. payment-service가 결제 완료/실패를 감지 → "payment-result" 토픽에 PaymentResultEvent 발행
 * 2. 본 컨슈머가 메시지를 수신 → consume() 메서드 호출
 * 3. consume()은 NotificationService.handlePaymentResult()에 처리를 위임한다
 *
 * 설계 의도:
 * - 컨슈머는 Kafka 메시지 수신이라는 단일 책임만 갖는다 (SRP 준수).
 * - 각 토픽별 컨슈머는 자체 properties로 역직렬화 타입을 명시하여
 *   PriceAlertConsumer와 독립적으로 동작할 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentResultConsumer {

    // 알림 처리를 위임할 서비스 (메시지 생성 + 발송)
    private final NotificationService notificationService;

    /**
     * payment-result 토픽에서 결제 결과 이벤트를 수신하여 NotificationService에 전달한다.
     *
     * @param event payment-service가 발행한 결제 결과 이벤트 (Envelope + Payload)
     *
     * 처리 흐름:
     * 1. Kafka로부터 이벤트를 수신한다.
     * 2. properties에 지정된 spring.json.value.default.type에 따라
     *    JSON이 PaymentResultEvent로 역직렬화된다.
     * 3. NotificationService.handlePaymentResult()에 이벤트를 전달해 알림 처리를 위임한다.
     * 4. 컨슈머 자체는 수신만 담당하며, 메시지 생성/발송 로직은 포함하지 않는다.
     */
    @KafkaListener(
            topics = "${app.kafka.topics.payment-result}",
            properties = {"spring.json.value.default.type=com.pbm.notification.dto.event.PaymentResultEvent"}
    )
    public void consume(PaymentResultEvent event) {
        // Kafka 리스너 메서드 진입 자체를 로그로 남겨 역직렬화 성공 여부를 빠르게 확인한다.
        log.info("[payment-result 리스너 진입] eventId={}, eventType={}", event.eventId(), event.eventType());

        // 수신한 이벤트를 NotificationService에 전달하여 알림 처리를 위임한다
        // 컨슈머는 수신만 담당하고, 실제 알림 메시지 생성/발송은 서비스 계층에서 수행한다
        notificationService.handlePaymentResult(event);
    }
}
