package com.pbm.payment.service;

import com.pbm.payment.domain.SessionKey;
import com.pbm.payment.dto.event.SessionKeyRegistrationEvent;
import com.pbm.payment.dto.event.SessionKeyRegistrationEventPayload;
import com.pbm.payment.repository.SessionKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.core.methods.response.TransactionReceipt;


/**
 * session-key-registration 토픽 Kafka 컨슈머.
 * <p>
 * 역할: price-service가 AUTO_PURCHASE 모니터링 조건 생성 시 발행하는
 *       SessionKeyRegistrationEvent를 수신하여 블록체인에 세션키를 등록한다.
 * 동작:
 *   1. 이벤트 수신
 *   2. WalletService로 userId에 해당하는 지갑 주소 조회
 *      (지갑이 없으면 기본 한도로 자동 생성)
 *   3. BlockchainService.fundAiAgent() → AI 에이전트에 가스비 ETH 전송
 *   4. BlockchainService.addSessionKey() → 컨트랙트에 세션키 등록
 * 연관: SessionKeyRegistrationEvent, BlockchainService, WalletService.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionKeyRegistrationConsumer {

    private final BlockchainService blockchainService;
    private final WalletService walletService;
    private final SessionKeyRepository sessionKeyRepository;

    /**
     * session-key-registration 토픽에서 이벤트를 수신하여 세션키를 블록체인에 등록한다.
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
            // 1. 사용자 지갑 조회 — 지갑이 없으면 명확한 예외를 발생시킨다.
            //    AUTO_PURCHASE 조건 등록 전에 반드시 지갑을 먼저 생성해야 한다.
            //    (POST /api/v1/wallet 호출 → 지갑 생성 → 조건 등록 순서)
            var wallet = walletService.findByUserId(payload.userId())
                    .orElseThrow(() -> new IllegalStateException(
                            "PBM 지갑이 존재하지 않습니다. AUTO_PURCHASE 조건 등록 전에 " +
                            "POST /api/v1/wallet 으로 지갑을 먼저 생성해주세요. userId=" + payload.userId()
                    ));

            // 2차 방어선: 세션키 한도가 지갑 전체 한도를 초과하면 등록 거부
            if (payload.limitKrw() > wallet.getWalletLimit().longValue()) {
                log.warn("세션키 등록 거부 - 세션키 한도({} KRW)가 지갑 한도({} KRW)를 초과합니다. " +
                                "subscriptionId: {}, userId: {}",
                        payload.limitKrw(), wallet.getWalletLimit(), payload.subscriptionId(), payload.userId());
                return;
            }

            String walletAddress = wallet.getWalletAddress();

            // 2. AI 에이전트에 가스비 ETH 전송 (마스터 지갑이 비용 부담 — 서비스 셋업 비용)
            blockchainService.fundAiAgent(payload.aiAgentAddress());
            log.info("AI 에이전트 ETH 지원 완료 - aiAgent: {}", payload.aiAgentAddress());

            // 3. 블록체인에 세션키 등록
            // ※ PBMSmartAccount.addSessionKey()는 require(msg.sender == owner)이므로
            //   사용자 EOA Credentials(DB에 저장된 개인키)로 서명해야 한다.
            // ※ PBM 차감(postOp)은 실제 AI 결제(executePayment) 시에만 수행한다.
            Credentials userCredentials = walletService.getUserCredentials(payload.userId());
            TransactionReceipt addKeyReceipt = blockchainService.addSessionKey(
                    walletAddress,
                    payload.aiAgentAddress(),
                    payload.limitKrw(),
                    payload.validSeconds(),
                    payload.platform(),
                    userCredentials
            );
            log.info("세션키 블록체인 등록 완료 - subscriptionId: {}, wallet: {}, aiAgent: {}",
                    payload.subscriptionId(), walletAddress, payload.aiAgentAddress());

            // 4. DB에 세션키 이력 저장
            SessionKey sessionKey = SessionKey.create(
                    payload.userId(),
                    payload.subscriptionId(),
                    walletAddress,
                    payload.aiAgentAddress(),
                    payload.aiAgentPrivateKey(),
                    payload.limitKrw(),
                    payload.validSeconds(),
                    payload.platform(),
                    addKeyReceipt.getTransactionHash()
            );
            sessionKeyRepository.save(sessionKey);
            log.info("세션키 DB 저장 완료 - subscriptionId: {}, sessionKeyId: {}, aiAgent: {}",
                    payload.subscriptionId(), sessionKey.getId(), payload.aiAgentAddress());

        } catch (Exception e) {
            log.error("세션키 등록 실패 - eventId: {}, subscriptionId: {}, 원인: {}",
                    event.eventId(), payload.subscriptionId(), e.getMessage(), e);
            // 세션키 등록 실패 시 재처리 로직은 추후 DLQ(Dead Letter Queue)로 확장 예정
        }
    }
}
