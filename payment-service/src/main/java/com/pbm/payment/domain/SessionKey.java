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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 블록체인에 등록된 AI 에이전트 세션키 엔티티.
 * <p>
 * 역할: price-service가 AUTO_PURCHASE 조건 생성 시 발행하는 세션키 등록 이벤트를
 *       payment-service가 수신하여 블록체인에 등록한 후 이 테이블에 이력을 저장한다.
 * 동작:
 *   - 세션키 등록 완료 시 create()로 ACTIVE 상태의 레코드를 생성한다.
 *   - 모니터링 종료/취소/만료 시 상태를 EXPIRED 또는 REVOKED로 전이한다.
 *   - 결제 실행 시 aiAgentPrivateKey를 꺼내 트랜잭션 서명에 사용한다.
 * 연관: SessionKeyRegistrationConsumer, SessionKeyStatus.
 */
@Getter
@Entity
@Table(
        name = "session_keys",
        indexes = {
                @Index(name = "idx_sk_user_id", columnList = "user_id"),
                @Index(name = "idx_sk_subscription_id", columnList = "subscription_id"),
                @Index(name = "idx_sk_wallet_address", columnList = "wallet_address"),
                @Index(name = "idx_sk_ai_agent_address", columnList = "ai_agent_address"),
                @Index(name = "idx_sk_status", columnList = "status")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SessionKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 사용자 식별자 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** price-service의 monitoring_subscriptions.id */
    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    /** 사용자의 PBM 스마트 지갑 주소 (0x...) */
    @Column(name = "wallet_address", nullable = false, length = 42)
    private String walletAddress;

    /** 이 조건 전용 AI 에이전트 이더리움 주소 (0x...) */
    @Column(name = "ai_agent_address", nullable = false, length = 42)
    private String aiAgentAddress;

    /**
     * AI 에이전트 개인키 (64자리 hex 문자열).
     * 목표 가격 달성 시 executeAIPayment() 서명에 사용된다.
     * 프로덕션에서는 반드시 암호화하여 저장해야 한다.
     */
    @Column(name = "ai_agent_private_key", nullable = false, length = 128)
    private String aiAgentPrivateKey;

    /** 세션키 한도 (KRW 기준) */
    @Column(name = "limit_krw", nullable = false)
    private Long limitKrw;

    /** 세션키 유효 기간 (초) */
    @Column(name = "valid_seconds", nullable = false)
    private Long validSeconds;

    /** 플랫폼 구분 (NAVER, COUPANG, ALIEXPRESS 등) */
    @Column(name = "platform", nullable = false, length = 30)
    private String platform;

    /** 세션키 상태 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SessionKeyStatus status;

    /** 세션키 만료 시각 (등록 시각 + validSeconds) */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** 블록체인 등록 트랜잭션 해시 */
    @Column(name = "register_tx_hash", length = 128)
    private String registerTxHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private SessionKey(Long userId, Long subscriptionId, String walletAddress,
                       String aiAgentAddress, String aiAgentPrivateKey,
                       Long limitKrw, Long validSeconds, String platform,
                       String registerTxHash) {
        this.userId = userId;
        this.subscriptionId = subscriptionId;
        this.walletAddress = walletAddress;
        this.aiAgentAddress = aiAgentAddress;
        this.aiAgentPrivateKey = aiAgentPrivateKey;
        this.limitKrw = limitKrw;
        this.validSeconds = validSeconds;
        this.platform = platform;
        this.status = SessionKeyStatus.ACTIVE;
        this.registerTxHash = registerTxHash;
    }

    /**
     * 블록체인 세션키 등록 완료 후 DB 레코드를 생성한다.
     *
     * @param userId            사용자 식별자
     * @param subscriptionId    price-service 구독 ID
     * @param walletAddress     사용자 스마트 지갑 주소
     * @param aiAgentAddress    AI 에이전트 주소
     * @param aiAgentPrivateKey AI 에이전트 개인키 (hex)
     * @param limitKrw          세션키 한도 (KRW)
     * @param validSeconds      유효 기간 (초)
     * @param platform          플랫폼 구분
     * @param registerTxHash    블록체인 등록 트랜잭션 해시
     * @return 생성된 SessionKey 엔티티 (상태: ACTIVE)
     */
    public static SessionKey create(Long userId, Long subscriptionId, String walletAddress,
                                    String aiAgentAddress, String aiAgentPrivateKey,
                                    Long limitKrw, Long validSeconds, String platform,
                                    String registerTxHash) {
        return new SessionKey(userId, subscriptionId, walletAddress,
                aiAgentAddress, aiAgentPrivateKey,
                limitKrw, validSeconds, platform, registerTxHash);
    }

    /**
     * 세션키를 만료 상태로 전이한다 (모니터링 종료 또는 유효기간 경과).
     */
    public void expire() {
        this.status = SessionKeyStatus.EXPIRED;
    }

    /**
     * 세션키를 취소 상태로 전이한다 (사용자 또는 관리자가 수동 취소).
     */
    public void revoke() {
        this.status = SessionKeyStatus.REVOKED;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        // 만료 시각 = 등록 시각 + 유효 기간
        this.expiresAt = now.plusSeconds(validSeconds);
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
