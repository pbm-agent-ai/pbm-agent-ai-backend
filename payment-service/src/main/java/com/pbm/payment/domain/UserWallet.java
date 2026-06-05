package com.pbm.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 사용자 PBM 스마트 컨트랙트 지갑 매핑 엔티티.
 * <p>
 * 역할: AccountFactory.createAccount()로 배포된 PBMSmartAccount 컨트랙트 주소를
 *       사용자 ID와 매핑하여 보관한다.
 * 동작: payment-service가 지갑 생성 시 이 레코드를 저장하고,
 *       이후 세션키 등록·결제 실행 시 userId로 지갑 주소를 조회한다.
 * <p>
 * 키 관리 방식 (CEX 커스터디 모델):
 *   서버가 사용자별 고유 키쌍(개인키/공개키)을 생성하고,
 *   개인키를 DB에 저장한다. 사용자는 자신의 개인키를 알지 못한다.
 *   (업비트·바이낸스와 동일한 커스터디 방식)
 *   ⚠️ 운영 환경에서는 AES-256 등으로 암호화하여 저장해야 한다.
 */
@Getter
@Entity
@Table(
        name = "user_wallets",
        indexes = {
                @Index(name = "idx_uw_user_id", columnList = "user_id", unique = true)
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserWallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 사용자 식별자 (auth-service userId와 동일) */
    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /**
     * 사용자 고유 EOA 주소 (0x...).
     * 서버가 생성한 키쌍에서 파생된 이더리움 주소.
     * PBMSmartAccount의 owner로 등록되어 세션키 관리 권한을 가진다.
     */
    @Column(name = "user_address", nullable = false, length = 42)
    private String userAddress;

    /**
     * 사용자 EOA 개인키 (64자리 hex, 0x 접두사 없음).
     * ⚠️ 운영 환경에서는 반드시 AES-256 등으로 암호화하여 저장해야 한다.
     * addSessionKey 등 owner 권한이 필요한 트랜잭션 서명에 사용된다.
     */
    @Column(name = "user_private_key", nullable = false, length = 64)
    private String userPrivateKey;

    /** 배포된 PBMSmartAccount 컨트랙트 주소 (0x...) */
    @Column(name = "wallet_address", nullable = false, length = 42)
    private String walletAddress;

    /**
     * 지갑 전체 PBM 한도 (KRW 기준).
     * addSessionKey 호출 시 sessionKey.limit 합계가 이 값을 초과할 수 없다.
     */
    @Column(name = "wallet_limit", nullable = false, precision = 19, scale = 0)
    private BigDecimal walletLimit;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private UserWallet(Long userId, String userAddress, String userPrivateKey,
                       String walletAddress, BigDecimal walletLimit) {
        this.userId = userId;
        this.userAddress = userAddress;
        this.userPrivateKey = userPrivateKey;
        this.walletAddress = walletAddress;
        this.walletLimit = walletLimit;
    }

    /**
     * 새 UserWallet 엔티티를 생성한다.
     *
     * @param userId         사용자 식별자
     * @param userAddress    사용자 EOA 주소 (PBMSmartAccount owner)
     * @param userPrivateKey 사용자 EOA 개인키 (64자리 hex)
     * @param walletAddress  배포된 PBMSmartAccount 주소
     * @param walletLimit    지갑 PBM 한도 (KRW 기준)
     * @return 생성된 UserWallet 엔티티
     */
    public static UserWallet create(Long userId, String userAddress, String userPrivateKey,
                                    String walletAddress, BigDecimal walletLimit) {
        return new UserWallet(userId, userAddress, userPrivateKey, walletAddress, walletLimit);
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
