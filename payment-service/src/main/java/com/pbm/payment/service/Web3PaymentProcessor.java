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
    private final TokenChargeService tokenChargeService;
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

            BlockchainService.PaymentExecutionResult execResult = blockchainService.executePayment(
                    walletAddress,
                    aiAgentPrivateKey,
                    recipientAddress,
                    payment.getAmount().longValue()
            );

            String txHash = execResult.txHash();
            log.info("블록체인 결제 성공 - paymentId: {}, txHash: {}", payment.getPaymentId(), txHash);

            // 결제 성공 시 토큰 차감 내역 DB 기록
            tokenChargeService.recordDeduction(
                    payment.getUserId(),
                    walletAddress,
                    payment.getAmount().longValue(),
                    payment.getPaymentId(),
                    txHash
            );

            // 결제 가스비 FEE DB 기록 (온체인 차감은 executePayment 내부에서 이미 완료)
            Integer gasFeeKrw = null;
            try {
                java.math.BigInteger pbmFee = blockchainService.convertEthToPbm(execResult.actualGasEthWei());
                java.math.BigInteger pbmFeeKrw = pbmFee.divide(java.math.BigInteger.TEN.pow(18));
                gasFeeKrw = pbmFeeKrw.intValue();

                tokenChargeService.recordFee(
                        payment.getUserId(),
                        payment.getSubscriptionId(),
                        walletAddress,
                        pbmFee,
                        txHash,
                        "결제 가스비"
                );
                log.info("결제 가스비 FEE 기록 완료 - paymentId: {}, gasFeeKrw: {}", payment.getPaymentId(), gasFeeKrw);
            } catch (Exception e) {
                log.warn("결제 가스비 FEE 기록 실패 (결제 자체는 성공) - paymentId: {}, 원인: {}",
                        payment.getPaymentId(), e.getMessage());
            }

            return PaymentProcessResult.success(txHash, "PBM 블록체인 결제 성공", gasFeeKrw);

        } catch (Exception e) {
            log.error("블록체인 결제 실패 - paymentId: {}, 원인: {}", payment.getPaymentId(), e.getMessage(), e);
            // 기술적인 raw 에러를 사용자 친화적인 실패 사유로 분류한다.
            String userFriendlyReason = classifyBlockchainError(e.getMessage());
            return PaymentProcessResult.failure("블록체인 결제 실패", userFriendlyReason);
        }
    }

    /**
     * 블록체인 예외 메시지를 사용자 친화적인 실패 사유로 분류한다.
     *
     * 역할: BlockchainService에서 발생하는 raw 오류 메시지 패턴을
     *       사용자가 이해할 수 있는 한국어 사유로 변환한다.
     *
     * 주요 패턴 (BlockchainService 기반):
     *   - "트랜잭션 전송 실패: 트랜잭션 오류: insufficient funds..."  → 토큰 잔액 부족
     *   - "eth_call 실패 - fn: validatePaymasterUserOp: ..."         → RPC 설정 오류
     *   - "트랜잭션 revert - ..."                                    → 스마트컨트랙트 거부
     *   - "지갑이 존재하지 않습니다 ..."                              → 지갑 미등록
     *   - "트랜잭션 전송 실패: ... timeout ..."                       → 네트워크 타임아웃
     *
     * @param rawMessage 블록체인 예외의 원본 메시지
     * @return 사용자에게 표시할 한국어 실패 사유
     */
    private String classifyBlockchainError(String rawMessage) {
        if (rawMessage == null) {
            return "알 수 없는 결제 오류";
        }
        String lower = rawMessage.toLowerCase();

        // 1. 잔액 부족 — "insufficient funds for gas * price + value"
        //    BlockchainService: "트랜잭션 전송 실패: 트랜잭션 오류: insufficient funds for gas..."
        if (lower.contains("insufficient funds") || lower.contains("insufficient balance")
                || lower.contains("잔액 부족")) {
            return "토큰 잔액 부족";
        }

        // 2. RPC/네트워크 설정 오류 — "eth_call 실패 - fn: ... Expected URL scheme 'http' or 'https'"
        //    또는 "no scheme was found" → RPC URL이 잘못 설정되어 있음 (인프라 문제)
        if (lower.contains("eth_call 실패") || lower.contains("url scheme")
                || lower.contains("no scheme was found") || lower.contains("expected url")) {
            return "결제 서버 설정 오류 (관리자 문의)";
        }

        // 3. 스마트컨트랙트 거부 — "트랜잭션 revert", "execution reverted"
        //    BlockchainService: "트랜잭션 revert - txHash: 0x..., status: 0x0"
        if (lower.contains("revert") || lower.contains("execution reverted")) {
            return "AI 오작동으로 인한 결제 실패 (스마트컨트랙트 지갑에서 거부)";
        }

        // 4. 지갑 미등록 — AI가 잘못된 페이지를 결제 페이지로 판단
        //    BlockchainService: "지갑이 존재하지 않습니다. createAccount가 완료되지 않았을 수 있습니다."
        if (lower.contains("지갑이 존재하지 않습니다") || lower.contains("wallet not found")
                || lower.contains("getaccount 결과 없음")) {
            return "AI 페이지 판단 오류로 인한 결제 실패 (지갑 미등록)";
        }

        // 5. 네트워크 타임아웃
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "네트워크 오류로 인한 결제 실패 (잠시 후 재시도)";
        }

        // 6. 연결 오류 — RPC 서버 자체에 연결 불가
        if (lower.contains("connection refused") || lower.contains("connection reset")
                || lower.contains("connection") && lower.contains("failed")) {
            return "블록체인 네트워크 연결 오류 (잠시 후 재시도)";
        }

        // 7. 가스비 / nonce 부족 — 네트워크 혼잡 또는 설정 오류
        if (lower.contains("nonce too low") || lower.contains("nonce")) {
            return "블록체인 트랜잭션 충돌 오류 (잠시 후 재시도)";
        }

        // 8. 트랜잭션 전송 실패 (위 패턴에 해당하지 않는 일반 전송 오류)
        if (lower.contains("트랜잭션 전송 실패") || lower.contains("트랜잭션 오류")) {
            return "트랜잭션 전송 실패";
        }

        // 9. 그 외
        return "AI 오작동으로 인한 결제 실패";
    }
}
