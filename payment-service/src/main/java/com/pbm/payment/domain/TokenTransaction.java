package com.pbm.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigInteger;
import java.time.Instant;

/**
 * PBM 토큰 충전·차감 내역 엔티티 (통합 원장).
 * <p>
 * 역할: 사용자 지갑의 모든 토큰 입출 이력을 단일 테이블로 관리한다.
 * 유형:
 *   - CHARGE: 마스터 지갑 → 사용자 스마트 지갑으로 PBM 토큰 전송 (충전)
 *   - DEDUCT: 결제 실행(executeAIPayment) 성공 후 차감 기록
 * 잔액 계산: type=CHARGE 합계 - type=DEDUCT 합계 (DB 기반 보조 잔액)
 */
@Getter
@Entity
@Table(
        name = "token_transactions",
        indexes = {
                @Index(name = "idx_tt_user_id", columnList = "user_id"),
                @Index(name = "idx_tt_user_type", columnList = "user_id, type"),
                @Index(name = "idx_tt_subscription_id", columnList = "subscription_id"),
                @Index(name = "idx_tt_user_subscription_type", columnList = "user_id, subscription_id, type")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TokenTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 사용자 식별자 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 모니터링 구독 ID. 상품별 수수료를 추적할 때 사용한다. */
    @Column(name = "subscription_id")
    private Long subscriptionId;

    /** 사용자 PBMSmartAccount 컨트랙트 주소 */
    @Column(name = "wallet_address", nullable = false, length = 42)
    private String walletAddress;

    /** 거래 유형 (CHARGE: 충전, DEDUCT: 결제 차감) */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private TokenTransactionType type;

    /**
     * 거래 토큰 수량 (wei 단위, 18 decimals).
     * 충전·차감 모두 양수로 저장하고 type으로 방향을 구분한다.
     */
    @Column(name = "amount_wei", nullable = false, precision = 78, scale = 0)
    private BigInteger amountWei;

    /** 블록체인 트랜잭션 해시 (성공 시 채워짐) */
    @Column(name = "tx_hash", length = 66)
    private String txHash;

    /** 거래 상태 (PENDING → SUCCESS / FAILED) */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private TokenTransactionStatus status;

    /** 거래 메모 (예: "결제 차감 - paymentId: abc123") */
    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private TokenTransaction(Long userId, Long subscriptionId, String walletAddress, TokenTransactionType type,
                             BigInteger amountWei, String note) {
        this.userId = userId;
        this.subscriptionId = subscriptionId;
        this.walletAddress = walletAddress;
        this.type = type;
        this.amountWei = amountWei;
        this.note = note;
        this.status = TokenTransactionStatus.PENDING;
    }

    /**
     * 충전 거래 내역을 PENDING 상태로 생성한다.
     *
     * @param userId        사용자 식별자
     * @param walletAddress 사용자 스마트 지갑 주소
     * @param amountWei     충전 토큰 수량 (wei)
     * @return 생성된 TokenTransaction
     */
    public static TokenTransaction createCharge(Long userId, String walletAddress, BigInteger amountWei) {
        return new TokenTransaction(userId, null, walletAddress, TokenTransactionType.CHARGE, amountWei, "PBM 토큰 충전");
    }

    /**
     * 결제 차감 내역을 SUCCESS 상태로 즉시 생성한다.
     * 차감은 블록체인 결제 성공 이후에만 기록되므로 초기 상태가 SUCCESS이다.
     *
     * @param userId        사용자 식별자
     * @param walletAddress 사용자 스마트 지갑 주소
     * @param amountWei     차감 토큰 수량 (wei)
     * @param paymentId     연관 결제 식별자 (메모용)
     * @param txHash        블록체인 트랜잭션 해시
     * @return 생성된 TokenTransaction
     */
    public static TokenTransaction createDeduct(Long userId, String walletAddress, BigInteger amountWei,
                                                String paymentId, String txHash) {
        TokenTransaction tx = new TokenTransaction(
                userId, null, walletAddress, TokenTransactionType.DEDUCT,
                amountWei, "결제 차감 - paymentId: " + paymentId
        );
        tx.status = TokenTransactionStatus.SUCCESS;
        tx.txHash = txHash;
        return tx;
    }

    /**
     * 가스비 차감 내역을 SUCCESS 상태로 즉시 생성한다.
     * postOp() 호출 성공 후에만 기록되므로 초기 상태가 SUCCESS이다.
     *
     * @param userId        사용자 식별자
     * @param walletAddress 사용자 스마트 지갑 주소
     * @param pbmFeeWei     차감된 PBM 수량 (wei, ETH→PBM 환산값)
     * @param refTxHash     수수료 원인이 된 트랜잭션 해시 (충전 or 세션키 등록)
     * @param feeTxHash     postOp 트랜잭션 해시
     * @param operationType 수수료 원인 동작 설명 (예: "토큰 충전", "세션키 등록")
     * @return 생성된 TokenTransaction
     */
    public static TokenTransaction createFee(Long userId, Long subscriptionId, String walletAddress, BigInteger pbmFeeWei,
                                             String refTxHash, String feeTxHash, String operationType) {
        TokenTransaction tx = new TokenTransaction(
                userId, subscriptionId, walletAddress, TokenTransactionType.FEE,
                pbmFeeWei, "가스비 차감 (" + operationType + ") - 원인 tx: " + refTxHash
        );
        tx.status = TokenTransactionStatus.SUCCESS;
        tx.txHash = feeTxHash;
        return tx;
    }

    /**
     * 블록체인 트랜잭션 성공 시 상태를 SUCCESS로 전이하고 txHash를 기록한다.
     *
     * @param txHash 블록체인 트랜잭션 해시
     */
    public void markSuccess(String txHash) {
        this.status = TokenTransactionStatus.SUCCESS;
        this.txHash = txHash;
    }

    /**
     * 블록체인 트랜잭션 실패 시 상태를 FAILED로 전이한다.
     */
    public void markFailed() {
        this.status = TokenTransactionStatus.FAILED;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = Instant.now();
    }
}
