package com.pbm.payment.service;

import com.pbm.payment.config.Web3Config;
import com.pbm.payment.domain.Payment;
import com.pbm.payment.repository.UserWalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;

/**
 * 실제 블록체인 Web3j 기반 결제 처리기.
 * <p>
 * 역할: Payment 엔티티의 AI 에이전트 개인키로 PBMSmartAccount.executeAIPayment()를 호출하여
 *       실제 PBM 토큰 결제를 처리한다.
 * 동작:
 *   1. payment.getAiAgentPrivateKey()가 존재하면 Web3j 블록체인 결제 진행
 *   2. 사용자 지갑 주소를 UserWalletRepository에서 조회
 *   3. BlockchainService.executePayment()로 executeAIPayment() 호출
 *   4. 트랜잭션 해시를 반환
 * 조건: OPENAI_MOCK_ENABLED=false(기본값)이거나 blockchain.enabled=true 일 때 활성화.
 * 폴백: aiAgentPrivateKey가 null이면 StubPaymentProcessor로 동작.
 * 연관: BlockchainService, UserWalletRepository, StubPaymentProcessor.
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class Web3PaymentProcessor implements PaymentProcessor {

    private final BlockchainService blockchainService;
    private final UserWalletRepository userWalletRepository;
    private final Credentials masterCredentials;
    private final StubPaymentProcessor stubPaymentProcessor;

    /**
     * 블록체인 결제를 처리한다.
     * <p>
     * aiAgentPrivateKey가 없으면 StubPaymentProcessor로 위임한다.
     * 지갑이 DB에 존재하지 않으면 결제 실패로 처리한다.
     *
     * @param payment 처리할 결제 엔티티
     * @return 블록체인 처리 결과
     */
    @Override
    public PaymentProcessResult process(Payment payment) {
        String aiAgentPrivateKey = payment.getAiAgentPrivateKey();

        // aiAgentPrivateKey가 없으면 스텁으로 폴백 (세션키 미등록 상태)
        if (aiAgentPrivateKey == null || aiAgentPrivateKey.isBlank()) {
            log.warn("AI 에이전트 개인키 없음 → 스텁 처리로 폴백 - paymentId: {}, userId: {}",
                    payment.getPaymentId(), payment.getUserId());
            return stubPaymentProcessor.process(payment);
        }

        // 사용자 지갑 주소 조회
        String walletAddress;
        try {
            walletAddress = userWalletRepository.findByUserId(payment.getUserId())
                    .map(w -> w.getWalletAddress())
                    .orElseThrow(() -> new IllegalStateException(
                            "사용자의 PBM 지갑이 존재하지 않습니다. userId=" + payment.getUserId()));
        } catch (Exception e) {
            log.error("지갑 주소 조회 실패 - paymentId: {}, userId: {}, 원인: {}",
                    payment.getPaymentId(), payment.getUserId(), e.getMessage());
            return PaymentProcessResult.failure("지갑 주소 조회 실패", e.getMessage());
        }

        // PBM 결제 수신 주소: MVP에서는 마스터 주소를 사용 (실제 서비스에서는 판매자 주소)
        String recipientAddress = masterCredentials.getAddress();

        try {
            log.info("Web3j 블록체인 결제 시작 - paymentId: {}, wallet: {}, amount: {} KRW",
                    payment.getPaymentId(), walletAddress, payment.getAmount());

            String txHash = blockchainService.executePayment(
                    walletAddress,
                    aiAgentPrivateKey,
                    recipientAddress,
                    payment.getAmount().longValue()
            );

            log.info("블록체인 결제 성공 - paymentId: {}, txHash: {}", payment.getPaymentId(), txHash);
            return PaymentProcessResult.success(txHash, "PBM 블록체인 결제 성공");

        } catch (Exception e) {
            log.error("블록체인 결제 실패 - paymentId: {}, 원인: {}", payment.getPaymentId(), e.getMessage(), e);
            return PaymentProcessResult.failure("블록체인 결제 실패", e.getMessage());
        }
    }
}
