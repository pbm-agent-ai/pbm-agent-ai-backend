package com.pbm.notification.service;

import com.pbm.notification.dto.event.PriceAlertEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * price-alert 토픽을 소비하는 Kafka 컨슈머 서비스.
 *
 * 역할: price-service가 발행한 가격 알림 이벤트를 Kafka에서 수신하여 NotificationService에 전달한다.
 * 동작: @KafkaListener가 "price-alert" 토픽의 메시지를 자동으로 폴링(polling)하여
 *       consume() 메서드를 호출한다. 컨슈머는 수신만 담당하고, 실제 알림 처리는 NotificationService에 위임한다.
 * 연관: PriceAlertEvent, NotificationService.
 *
 * Kafka 컨슈머 동작 흐름:
 * 1. price-service가 목표 가격 도달을 감지 → "price-alert" 토픽에 PriceAlertEvent 발행
 * 2. 본 컨슈머가 메시지를 수신 → consume() 메서드 호출
 * 3. consume()은 NotificationService.handlePriceAlert()에 처리를 위임한다
 *
 * 설계 의도:
 * - 컨슈머는 Kafka 메시지 수신이라는 단일 책임만 갖는다 (SRP 준수).
 * - 메시지 포맷팅/발송 로직은 NotificationService + NotificationSender로 분리되어 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceAlertConsumer {

    // 알림 처리를 위임할 서비스 (메시지 생성 + 발송)
    private final NotificationService notificationService;

    /**
     * price-alert 토픽에서 가격 알림 이벤트를 수신하여 NotificationService에 전달한다.
     *
     * @param event price-service가 발행한 가격 알림 이벤트 (Envelope + Payload)
     *
     * 처리 흐름:
     * 1. Kafka로부터 이벤트를 수신한다.
     * 2. NotificationService.handlePriceAlert()에 이벤트를 전달해 알림 처리를 위임한다.
     * 3. 컨슈머 자체는 수신만 담당하며, 메시지 생성/발송 로직은 포함하지 않는다.
     */
    @KafkaListener(topics = "${app.kafka.topics.price-alert}")
    public void consume(PriceAlertEvent event) {
        // Kafka 리스너 메서드 진입 자체를 로그로 남겨 역직렬화 성공 여부를 빠르게 확인한다.
        log.info("[price-alert 리스너 진입] eventId={}, eventType={}", event.eventId(), event.eventType());

        // 수신한 이벤트를 NotificationService에 전달하여 알림 처리를 위임한다
        // 컨슈머는 수신만 담당하고, 실제 알림 메시지 생성/발송은 서비스 계층에서 수행한다
        notificationService.handlePriceAlert(event);
    }
}
