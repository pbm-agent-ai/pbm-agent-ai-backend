package com.pbm.payment.service;

import com.pbm.payment.domain.SessionKey;
import com.pbm.payment.domain.UserWallet;
import com.pbm.payment.repository.SessionKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.math.BigInteger;

/**
 * 세션키 등록 서비스.
 * <p>
 * 역할: AI 에이전트 세션키를 블록체인에 등록하고 DB에 저장한다.
 * 동작:
 *   1. 사용자 지갑 조회
 *   2. 지갑 한도 검증
 *   3. AI 에이전트 ETH 지원 (부족분만)
 *   4. 사용자 EOA ETH 지원 (부족분만)
 *   5. addSessionKey() 호출
 *   6. DB에 세션키 저장
 *   7. 가스비 수수료 차감
 * 연관: BlockchainService, WalletService, SessionKeyRepository, TokenChargeService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionKeyService {

    private final BlockchainService blockchainService;
    private final WalletService walletService;
    private final SessionKeyRepository sessionKeyRepository;
    private final TokenChargeService tokenChargeService;
    private final SessionKeyProgressService sessionKeyProgressService;

    /**
     * 세션키를 동기적으로 등록한다.
     * <p>
     * price-service에서 즉시 충전 조건이 충족되었을 때 REST API를 통해 호출된다.
     * Kafka 이벤트 기반 비동기 등록과 달리, 클라이언트가 응답을 받을 때까지
     * 블록체인 등록 완료 및 DB 저장이 모두 완료됨을 보장한다.
     *
     * @param userId             사용자 ID
     * @param subscriptionId     모니터링 구독 ID
     * @param aiAgentAddress     AI 에이전트 주소
     * @param aiAgentPrivateKey  AI 에이전트 개인키
     * @param limitKrw           세션키 한도 (KRW)
     * @param validSeconds       유효 기간 (초)
     * @param platform           플랫폼 (NAVER, ALIEXPRESS 등)
     * @return 세션키 등록 결과 (세션키 ID, 트랜잭션 해시, 지갑 주소, AI 에이전트 주소 포함)
     * @throws IllegalStateException    지갑이 없거나 블록체인 등록 실패 시
     * @throws IllegalArgumentException 세션키 한도가 지갑 한도를 초과할 경우
     */
    @Transactional
    public SessionKeyRegistrationResult registerSessionKey(
            Long userId,
            Long subscriptionId,
            String aiAgentAddress,
            String aiAgentPrivateKey,
            long limitKrw,
            long validSeconds,
            String platform
    ) {
        log.info("세션키 등록 시작 (동기) - userId: {}, subscriptionId: {}, aiAgent: {}",
                userId, subscriptionId, aiAgentAddress);

        try {
            // 1. 사용자 지갑 조회
            sessionKeyProgressService.emit(userId, "WALLET_CHECK", "사용자 지갑 조회 중");
            UserWallet wallet = walletService.findByUserId(userId)
                    .orElseThrow(() -> new IllegalStateException(
                            "PBM 지갑이 존재하지 않습니다. AUTO_PURCHASE 조건 등록 전에 " +
                            "POST /api/v1/wallet 으로 지갑을 먼저 생성해주세요. userId=" + userId));

            // 2. 지갑 한도 검증
            sessionKeyProgressService.emit(userId, "LIMIT_VALIDATED", "세션키 한도 검증 중");
            if (limitKrw > wallet.getWalletLimit().longValue()) {
                throw new IllegalArgumentException(
                        String.format("세션키 한도(%d KRW)가 지갑 한도(%s KRW)를 초과합니다.",
                                limitKrw, wallet.getWalletLimit().toPlainString()));
            }

            String walletAddress = wallet.getWalletAddress();

            // 3. AI 에이전트 ETH 지원 (부족분만)
            sessionKeyProgressService.emit(userId, "AI_AGENT_FUNDING", "AI 에이전트 ETH 지원 중");
            BigInteger currentGasPrice = blockchainService.getGasPrice();
            BigInteger requiredGasForAiAgent = BlockchainService.GAS_LIMIT.multiply(currentGasPrice)
                    .multiply(BigInteger.TWO);
            blockchainService.ensureAiAgentFunded(aiAgentAddress, requiredGasForAiAgent);
            sessionKeyProgressService.emit(userId, "AI_AGENT_FUNDED", "AI 에이전트 ETH 지원 완료", aiAgentAddress);

            // 4. 사용자 EOA ETH 지원 (부족분만)
            sessionKeyProgressService.emit(userId, "USER_EOA_FUNDING", "사용자 EOA ETH 지원 중");
            BigInteger requiredGasForUserEoa = BlockchainService.GAS_LIMIT.multiply(currentGasPrice)
                    .multiply(BigInteger.TWO);
            blockchainService.ensureUserEoaFunded(wallet.getUserAddress(), requiredGasForUserEoa);
            sessionKeyProgressService.emit(userId, "USER_EOA_FUNDED", "사용자 EOA ETH 지원 완료", wallet.getUserAddress());

            // 5. addSessionKey() 호출
            sessionKeyProgressService.emit(userId, "BLOCKCHAIN_REGISTERING", "블록체인 세션키 등록 중");
            Credentials userCredentials = walletService.getUserCredentials(userId);
            TransactionReceipt addKeyReceipt = blockchainService.addSessionKey(
                    walletAddress,
                    aiAgentAddress,
                    limitKrw,
                    validSeconds,
                    platform,
                    userCredentials
            );
            log.info("세션키 블록체인 등록 완료 - subscriptionId: {}, txHash: {}",
                    subscriptionId, addKeyReceipt.getTransactionHash());
            sessionKeyProgressService.emit(userId, "BLOCKCHAIN_REGISTERED",
                    "블록체인 세션키 등록 완료", addKeyReceipt.getTransactionHash());

            // 6. DB에 세션키 저장
            sessionKeyProgressService.emit(userId, "DB_SAVING", "세션키 DB 저장 중");
            SessionKey sessionKey = SessionKey.create(
                    userId,
                    subscriptionId,
                    walletAddress,
                    aiAgentAddress,
                    aiAgentPrivateKey,
                    limitKrw,
                    validSeconds,
                    platform,
                    addKeyReceipt.getTransactionHash()
            );
            sessionKeyRepository.save(sessionKey);
            log.info("세션키 DB 저장 완료 - subscriptionId: {}, sessionKeyId: {}",
                    subscriptionId, sessionKey.getId());
            sessionKeyProgressService.emit(userId, "DB_SAVED", "세션키 DB 저장 완료",
                    String.valueOf(sessionKey.getId()));

            // 7. 가스비 수수료 차감
            sessionKeyProgressService.emit(userId, "GAS_FEE_CHARGING", "가스비 수수료 차감 중");
            tokenChargeService.chargeGasFee(userId, subscriptionId, walletAddress, addKeyReceipt, "세션키 등록");
            sessionKeyProgressService.emit(userId, "GAS_FEE_CHARGED", "가스비 수수료 차감 완료");

            SessionKeyRegistrationResult result = new SessionKeyRegistrationResult(
                    sessionKey.getId(),
                    addKeyReceipt.getTransactionHash(),
                    walletAddress,
                    aiAgentAddress
            );

            sessionKeyProgressService.complete(userId, addKeyReceipt.getTransactionHash());
            return result;

        } catch (Exception e) {
            sessionKeyProgressService.error(userId, e.getMessage());
            throw e;
        }
    }

    /**
     * 세션키 등록 결과 DTO.
     * <p>
     * REST API 응답으로 사용되며, 클라이언트가 등록된 세션키의 정보를 확인할 수 있도록 한다.
     *
     * @param sessionKeyId     DB에 저장된 세션키 ID
     * @param transactionHash  블록체인 등록 트랜잭션 해시
     * @param walletAddress    사용자 PBM 스마트 지갑 주소
     * @param aiAgentAddress   등록된 AI 에이전트 주소
     */
    public record SessionKeyRegistrationResult(
            Long sessionKeyId,
            String transactionHash,
            String walletAddress,
            String aiAgentAddress
    ) {}
}
