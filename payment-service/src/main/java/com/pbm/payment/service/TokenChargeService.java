package com.pbm.payment.service;

import com.pbm.payment.domain.TokenTransaction;
import com.pbm.payment.domain.TokenTransactionType;
import com.pbm.payment.domain.UserWallet;
import com.pbm.payment.dto.response.TokenChargeResponse;
import com.pbm.payment.dto.response.TokenTransactionResponse;
import com.pbm.payment.repository.TokenTransactionRepository;
import com.pbm.payment.repository.UserWalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;

/**
 * PBM 토큰 충전 서비스.
 * <p>
 * 역할: 사용자 스마트 지갑에 PBM 토큰을 충전하고, 진행 단계를 SSE로 실시간 전송한다.
 * 동작:
 *   1. 지갑 조회 → 2. DB PENDING 저장 → 3. 블록체인 transfer
 *   4. 충전 확정 → 5. 가스비 계산 → 6. 가스비 postOp 차감 → 7. 완료
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TokenChargeService {

    /** PBM 토큰 소수점: 18자리 */
    private static final BigInteger TOKEN_DECIMALS = BigInteger.TEN.pow(18);
    private static final BigDecimal TOKEN_DECIMALS_DEC = BigDecimal.TEN.pow(18);

    private final TokenTransactionRepository tokenTransactionRepository;
    private final UserWalletRepository userWalletRepository;
    private final BlockchainService blockchainService;
    private final ChargeProgressService chargeProgressService;

    /**
     * 사용자 스마트 지갑에 PBM 토큰을 충전한다.
     * 각 단계마다 SSE 이벤트를 통해 프론트엔드에 진행 상황을 실시간으로 전송한다.
     *
     * @param userId    사용자 식별자
     * @param amountPbm 충전 수량 (PBM 단위, 정수)
     * @return 충전 결과 응답 DTO
     */
    @Transactional
    public TokenChargeResponse charge(Long userId, Long amountPbm) {
        // 0. 입력값 검증 (최소 10,000 PBM: 가스비 ~2,145 PBM 감안 안전 하한선)
        if (amountPbm == null || amountPbm < 10_000) {
            chargeProgressService.error(userId, "최소 충전 금액은 10,000 PBM입니다.");
            throw new IllegalArgumentException("최소 충전 금액은 10,000 PBM입니다.");
        }

        // ── STEP 1: 요청 수신 ──
        chargeProgressService.emit(userId, "REQUEST_RECEIVED", "충전 요청 수신",
                amountPbm + " PBM 충전 요청");

        // 1. 사용자 지갑 조회
        UserWallet wallet = userWalletRepository.findByUserId(userId)
                .orElseThrow(() -> {
                    chargeProgressService.error(userId, "PBM 지갑이 없습니다.");
                    return new IllegalStateException("PBM 지갑이 없습니다. userId=" + userId);
                });

        BigInteger amountWei = BigInteger.valueOf(amountPbm).multiply(TOKEN_DECIMALS);
        String walletAddress = wallet.getWalletAddress();

        // ── STEP 2: DB 저장 ──
        TokenTransaction tx = TokenTransaction.createCharge(userId, walletAddress, amountWei);
        tokenTransactionRepository.save(tx);
        chargeProgressService.emit(userId, "DB_SAVED", "충전 내역 저장 완료 (처리 대기 중)");

        try {
            // ── STEP 3·4: 블록체인 transfer (TX_SENT / TX_CONFIRMED 콜백) ──
            TransactionReceipt receipt = blockchainService.transferPbmToken(
                    walletAddress, amountWei,
                    signal -> {
                        if (signal.startsWith("TX_SENT:")) {
                            String txHash = signal.substring("TX_SENT:".length());
                            chargeProgressService.emit(userId, "TX_SENT",
                                    "트랜잭션 전송 완료 — 블록 확정 대기 중",
                                    "txHash: " + txHash);
                        } else if (signal.startsWith("TX_CONFIRMED:")) {
                            String blockNumber = signal.substring("TX_CONFIRMED:".length());
                            chargeProgressService.emit(userId, "TX_CONFIRMED",
                                    "트랜잭션 확정 완료",
                                    "블록 #" + blockNumber);
                        }
                    }
            );
            String txHash = receipt.getTransactionHash();

            // ── STEP 5: 충전 DB 업데이트 ──
            tx.markSuccess(txHash);
            tokenTransactionRepository.save(tx);
            chargeProgressService.emit(userId, "CHARGE_COMPLETED",
                    String.format("%,d PBM 충전 완료", amountPbm),
                    "txHash: " + txHash);

            // ── STEP 6: 가스비 계산 ──
            chargeGasFeeWithProgress(userId, walletAddress, receipt);

            // 완료
            chargeProgressService.complete(userId);
            return TokenChargeResponse.from(tx);

        } catch (Exception e) {
            tx.markFailed();
            tokenTransactionRepository.save(tx);
            chargeProgressService.error(userId, e.getMessage());
            log.error("토큰 충전 실패 - userId: {}, 원인: {}", userId, e.getMessage(), e);
            throw new RuntimeException("PBM 토큰 충전에 실패했습니다: " + e.getMessage(), e);
        }
    }

    /**
     * 가스비를 PBM으로 차감하고 SSE로 단계별 진행 상황을 전송한다.
     * charge() 내부에서만 호출된다.
     */
    private void chargeGasFeeWithProgress(Long userId, String walletAddress, TransactionReceipt receipt) {
        try {
            // STEP 6: 가스비 계산
            BigInteger ethFee = blockchainService.calculateEthFee(receipt);
            BigInteger pbmFee = blockchainService.convertEthToPbm(ethFee);
            BigDecimal ethFeeEth = new BigDecimal(ethFee)
                    .divide(TOKEN_DECIMALS_DEC, 8, RoundingMode.DOWN);
            BigDecimal pbmFeeHuman = new BigDecimal(pbmFee)
                    .divide(TOKEN_DECIMALS_DEC, 4, RoundingMode.DOWN);
            chargeProgressService.emit(userId, "GAS_CALCULATED", "가스비 계산 완료",
                    ethFeeEth.toPlainString() + " ETH → " + pbmFeeHuman.toPlainString() + " PBM");

            // STEP 7: 가스비 차감 시작
            chargeProgressService.emit(userId, "GAS_DEDUCT_STARTED", "가스비 PBM 차감 시작");

            // STEP 8·9: postOp 트랜잭션 (GAS_TX_SENT / GAS_TX_CONFIRMED 콜백)
            blockchainService.deductGasCostFromWallet(walletAddress, ethFee, signal -> {
                if (signal.startsWith("GAS_TX_SENT:")) {
                    String gasTxHash = signal.substring("GAS_TX_SENT:".length());
                    chargeProgressService.emit(userId, "GAS_TX_SENT",
                            "가스비 차감 트랜잭션 전송 완료 — 확정 대기 중",
                            "txHash: " + gasTxHash);
                } else if ("GAS_TX_CONFIRMED".equals(signal)) {
                    chargeProgressService.emit(userId, "GAS_TX_CONFIRMED",
                            "가스비 차감 트랜잭션 확정 완료");
                }
            });

            // STEP 10: DB FEE 기록
            TokenTransaction feeTx = TokenTransaction.createFee(
                    userId, null, walletAddress, pbmFee,
                    receipt.getTransactionHash(), receipt.getTransactionHash(), "토큰 충전"
            );
            tokenTransactionRepository.save(feeTx);
            chargeProgressService.emit(userId, "GAS_DEDUCT_COMPLETED",
                    "가스비 차감 완료",
                    pbmFeeHuman.toPlainString() + " PBM 차감");

            log.info("가스비 수수료 차감 완료 - userId: {}, ethFee: {} wei, pbmFee: {} wei",
                    userId, ethFee, pbmFee);

        } catch (Exception e) {
            log.error("가스비 수수료 차감 실패 - userId: {}, 원인: {}", userId, e.getMessage(), e);
            chargeProgressService.emit(userId, "GAS_DEDUCT_FAILED",
                    "가스비 차감 실패 (충전은 정상 완료)", e.getMessage());
        }
    }

    /**
     * 가스비 차감 (SSE 없음) — SessionKeyRegistrationConsumer 등 외부 호출용.
     */
    @Transactional
    public void chargeGasFee(Long userId, Long subscriptionId, String walletAddress, TransactionReceipt receipt,
                             String operationType) {
        try {
            BigInteger ethFee = blockchainService.calculateEthFee(receipt);
            blockchainService.deductGasCostFromWallet(walletAddress, ethFee);
            BigInteger pbmFee = blockchainService.convertEthToPbm(ethFee);
            TokenTransaction feeTx = TokenTransaction.createFee(
                    userId, subscriptionId, walletAddress, pbmFee,
                    receipt.getTransactionHash(), receipt.getTransactionHash(), operationType
            );
            tokenTransactionRepository.save(feeTx);
            log.info("가스비 수수료 차감 완료 - userId: {}, operationType: {}, ethFee: {} wei",
                    userId, operationType, ethFee);
        } catch (Exception e) {
            log.error("가스비 수수료 차감 실패 - userId: {}, 원인: {}", userId, e.getMessage(), e);
        }
    }

    /** 사용자 전체 거래 내역 조회 */
    public List<TokenTransactionResponse> getTransactions(Long userId) {
        return tokenTransactionRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(TokenTransactionResponse::from).toList();
    }

    /** 충전 내역만 조회 */
    public List<TokenTransactionResponse> getChargeHistory(Long userId) {
        return tokenTransactionRepository
                .findByUserIdAndTypeOrderByCreatedAtDesc(userId, TokenTransactionType.CHARGE)
                .stream().map(TokenTransactionResponse::from).toList();
    }

    /**
     * 가스비 FEE 내역만 DB에 기록한다 (온체인 차감은 이미 완료된 상태).
     * Web3PaymentProcessor에서 결제 가스비 기록 시 사용한다.
     *
     * @param userId        사용자 식별자
     * @param walletAddress 사용자 스마트 지갑 주소
     * @param pbmFeeWei     가스비 PBM (wei 단위)
     * @param refTxHash     수수료 원인이 된 트랜잭션 해시
     * @param operationType 수수료 원인 동작 설명
     */
    @Transactional
    public void recordFee(Long userId, Long subscriptionId, String walletAddress, BigInteger pbmFeeWei,
                          String refTxHash, String operationType) {
        TokenTransaction feeTx = TokenTransaction.createFee(
                userId, subscriptionId, walletAddress, pbmFeeWei,
                refTxHash, refTxHash, operationType
        );
        tokenTransactionRepository.save(feeTx);
        log.info("가스비 FEE 내역 기록 완료 - userId: {}, operationType: {}", userId, operationType);
    }

    /** 결제 성공 시 토큰 차감 내역 기록 */
    @Transactional
    public void recordDeduction(Long userId, String walletAddress, long amountKrw,
                                String paymentId, String txHash) {
        BigInteger amountWei = BigInteger.valueOf(amountKrw).multiply(TOKEN_DECIMALS);
        TokenTransaction tx = TokenTransaction.createDeduct(userId, walletAddress, amountWei, paymentId, txHash);
        tokenTransactionRepository.save(tx);
        log.info("토큰 차감 내역 기록 - userId: {}, paymentId: {}", userId, paymentId);
    }
}
