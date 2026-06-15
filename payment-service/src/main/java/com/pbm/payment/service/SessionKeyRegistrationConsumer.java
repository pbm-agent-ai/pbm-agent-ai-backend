package com.pbm.payment.service;

import com.pbm.payment.dto.event.SessionKeyRegistrationEvent;
import com.pbm.payment.dto.event.SessionKeyRegistrationEventPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


/**
 * session-key-registration 토픽 Kafka 컨슈머.
 * <p>
 * 역할: price-service가 AUTO_PURCHASE 모니터링 조건 생성 시 발행하는
 *       SessionKeyRegistrationEvent를 수신하여 블록체인에 세션키를 등록한다.
 * 동작:
 *   1. 이벤트 수신
 *   2. SessionKeyService.registerSessionKey() 호출 (모든 등록 로직 위임)
 * 연관: SessionKeyRegistrationEvent, SessionKeyService.
 * <p>
 * 참고: 동기 방식의 REST API가 필요하면 SessionKeyController.registerSessionKey()를,
 *       비동기 Kafka 방식이 필요하면 이 컨슈머를 사용한다.
 *       실제 등록 로직은 SessionKeyService에 통합되어 있어 중복이 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionKeyRegistrationConsumer {

    private final SessionKeyService sessionKeyService;

    /**
     * session-key-registration 토픽에서 이벤트를 수신하여 세션키를 블록체인에 등록한다.
     * <p>
     * 모든 실제 등록 로직은 SessionKeyService.registerSessionKey()에 위임하여
     * REST API(Kafka 기반)와 Kafka 이벤트 기반 등록 간의 로직 중복을 방지한다.
     *
     * @param event 세션키 등록 요청 이벤트
     */
    @KafkaListener(
            topics = "${app.kafka.topics.session-key-registration}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "sessionKeyRegistrationKafkaListenerContainerFactory"
    )
    public void consume(SessionKeyRegistrationEvent event) {
        SessionKeyRegistrationEventPayload payload = event.payload();
        log.info("세션키 등록 이벤트 수신 - eventId: {}, userId: {}, subscriptionId: {}, aiAgent: {}",
                event.eventId(), payload.userId(), payload.subscriptionId(), payload.aiAgentAddress());

        try {
            // SessionKeyService에 등록 로직 위임
            // REST API(SessionKeyController)와 동일한 로직을 사용하므로 중복 없음
            sessionKeyService.registerSessionKey(
                    payload.userId(),
                    payload.subscriptionId(),
                    payload.aiAgentAddress(),
                    payload.aiAgentPrivateKey(),
                    payload.limitKrw(),
                    payload.validSeconds(),
                    payload.platform()
            );
            log.info("세션키 등록 이벤트 처리 완료 - eventId: {}, subscriptionId: {}",
                    event.eventId(), payload.subscriptionId());

        } catch (Exception e) {
            log.error("세션키 등록 실패 - eventId: {}, subscriptionId: {}, 원인: {}",
                    event.eventId(), payload.subscriptionId(), e.getMessage(), e);
            // 세션키 등록 실패 시 재처리 로직은 추후 DLQ(Dead Letter Queue)로 확장 예정
        }
    }
}
