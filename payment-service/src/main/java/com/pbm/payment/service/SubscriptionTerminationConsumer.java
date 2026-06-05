package com.pbm.payment.service;

import com.pbm.payment.domain.SessionKey;
import com.pbm.payment.domain.SessionKeyStatus;
import com.pbm.payment.dto.event.SubscriptionTerminationEvent;
import com.pbm.payment.dto.event.SubscriptionTerminationEventPayload;
import com.pbm.payment.repository.SessionKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;

import java.util.Optional;

/**
 * subscription-termination 토픽 Kafka 컨슈머.
 * <p>
 * 역할: price-service가 구독 취소/만료 시 발행하는 이벤트를 수신하여
 *       세션키 상태를 DB에 반영하고, 취소(CANCELLED)의 경우 블록체인에서도 revoke한다.
 * 동작:
 *   - CANCELLED: revokeSessionKey() 온체인 호출 → DB status = REVOKED
 *   - EXPIRED:   온체인은 validSeconds로 이미 만료됐으므로 DB status = EXPIRED만 업데이트
 * 연관: SessionKeyRepository, BlockchainService, WalletService.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionTerminationConsumer {

    private final SessionKeyRepository sessionKeyRepository;
    private final BlockchainService blockchainService;
    private final WalletService walletService;

    @KafkaListener(
            topics = "${app.kafka.topics.subscription-termination}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "subscriptionTerminationKafkaListenerContainerFactory"
    )
    public void consume(SubscriptionTerminationEvent event) {
        SubscriptionTerminationEventPayload payload = event.payload();
        log.info("구독 종료 이벤트 수신 - eventId: {}, subscriptionId: {}, userId: {}, reason: {}",
                event.eventId(), payload.subscriptionId(), payload.userId(), payload.reason());

        Optional<SessionKey> sessionKeyOpt = sessionKeyRepository.findBySubscriptionIdAndStatus(
                payload.subscriptionId(), SessionKeyStatus.ACTIVE
        );

        if (sessionKeyOpt.isEmpty()) {
            log.info("ACTIVE 세션키 없음 - 처리 생략. subscriptionId: {}", payload.subscriptionId());
            return;
        }

        SessionKey sessionKey = sessionKeyOpt.get();

        if ("CANCELLED".equals(payload.reason())) {
            handleCancelled(sessionKey, payload);
        } else {
            // EXPIRED: 온체인은 이미 만료, DB만 업데이트
            sessionKey.expire();
            sessionKeyRepository.save(sessionKey);
            log.info("세션키 EXPIRED 처리 완료 - sessionKeyId: {}, subscriptionId: {}",
                    sessionKey.getId(), payload.subscriptionId());
        }
    }

    private void handleCancelled(SessionKey sessionKey, SubscriptionTerminationEventPayload payload) {
        // 1. 블록체인 revoke (사용자 owner 키 필요)
        try {
            Credentials userCredentials = walletService.getUserCredentials(payload.userId());
            blockchainService.revokeSessionKey(
                    sessionKey.getWalletAddress(),
                    sessionKey.getAiAgentAddress(),
                    userCredentials
            );
            log.info("세션키 온체인 revoke 완료 - sessionKeyId: {}, aiAgent: {}",
                    sessionKey.getId(), sessionKey.getAiAgentAddress());
        } catch (Exception e) {
            // 온체인 revoke 실패 시에도 DB는 반드시 업데이트 (best-effort)
            log.error("세션키 온체인 revoke 실패 - sessionKeyId: {}, 원인: {}. DB는 REVOKED로 처리합니다.",
                    sessionKey.getId(), e.getMessage(), e);
        }

        // 2. DB status = REVOKED
        sessionKey.revoke();
        sessionKeyRepository.save(sessionKey);
        log.info("세션키 REVOKED 처리 완료 - sessionKeyId: {}, subscriptionId: {}",
                sessionKey.getId(), payload.subscriptionId());
    }
}
