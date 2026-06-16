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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * PBM 스마트컨트랙트 자동 결제 엔티티.
 *
 * 역할: 사용자 요청에 따라 발생한 결제 건의 생애주기(PENDING → SUCCESS/FAILED)를 추적한다.
 * 동작: create() 정적 팩토리로 초기 상태(PENDING)를 생성하고,
 *       블록체인 트랜잭션 결과에 따라 markSuccess() 또는 markFailed()로 상태를 전이시킨다.
 * 연관: PaymentStatus.
 */
@Getter     // 모든 필드의 get메서드 자동 생성 (Lombok)
@Entity     // 이 클래스는 DB 테이블이다.라고 JPA에게 선언
@Table(
        name = "payments",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_payments_payment_id",
                columnNames = {"payment_id"}
        ),
        indexes = {
                @Index(name = "idx_payments_subscription_id", columnList = "subscription_id")
        }
)
// 파라미터 없는 생성자를 자동 생성하되, 접근 범위를 protected로 제한
// JPA 내부에서 객체 복원할 때 필요하지만, 외부 new Payment()는 못 하게 막음
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    // JPA가 기본 키를 자동 생성한다.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 외부 결제 식별자. Kafka 메시지나 블록체인 연동 시 이 값을 기준으로 결제 건을 추적한다.
    @Column(name = "payment_id", nullable = false, length = 64)
    private String paymentId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 모니터링 구독 ID. 상품별 수수료와 결제 내역을 연결하는 데 사용한다. */
    @Column(name = "subscription_id")
    private Long subscriptionId;

    // 상품명 또는 원본 명령어 (URL 포함 명령어는 255자를 초과할 수 있으므로 TEXT 타입)
    @Column(name = "product_name", nullable = false, columnDefinition = "TEXT")
    private String productName;

    @Column(name = "product_url", columnDefinition = "TEXT")
    private String productUrl;

    @Column(name = "amount", nullable = false)
    private Integer amount;

    // 결제 통화 코드. KRW, USD 등 ISO 통화 코드를 문자열로 저장한다.
    @Column(name = "currency", nullable = false, length = 10)
    private String currency;

    // 결제 상태를 문자열 enum으로 저장하여 DB에서도 PENDING/SUCCESS/FAILED 값을 바로 읽을 수 있게 한다.
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    // 블록체인 트랜잭션 해시. 결제 성공 시에만 값이 채워진다.
    @Column(name = "transaction_hash", length = 128)
    private String transactionHash;

    // 결제 실패 원인 메시지. 결제 실패 시에만 값이 채워진다.
    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    /** 결제 가스비 (KRW 단위 정수, 결제 성공 시에만 값이 채워짐) */
    @Column(name = "gas_fee_krw")
    private Integer gasFeeKrw;

    /** 상품 이미지 URL (모니터링 구독의 snapshotImageUrl에서 전달받음) */
    @Column(name = "product_image_url", columnDefinition = "TEXT")
    private String productImageUrl;

    /**
     * AI 에이전트 개인키 (조건별 세션키 서명용).
     * executeAIPayment 호출 시 이 키로 트랜잭션에 서명한다.
     * 보안 강화가 필요한 프로덕션에서는 암호화하여 저장해야 한다.
     */
    @Column(name = "ai_agent_private_key", length = 128)
    private String aiAgentPrivateKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // private 생성자로 클래스 내부에서만 호출 가능하도록 만듦
    private Payment(String paymentId, Long userId, Long subscriptionId, String productName, String productUrl,
                    Integer amount, String currency, String aiAgentPrivateKey, String productImageUrl) {
        this.paymentId = paymentId;
        this.userId = userId;
        this.subscriptionId = subscriptionId;
        this.productName = productName;
        this.productUrl = productUrl;
        this.amount = amount;
        this.currency = currency;
        this.aiAgentPrivateKey = aiAgentPrivateKey;
        this.productImageUrl = productImageUrl;
        // 초기 상태는 PENDING, 트랜잭션 해시와 실패 사유는 null로 둔다.
        this.status = PaymentStatus.PENDING;
        this.transactionHash = null;
        this.failureReason = null;
    }

    /**
     * 새 결제 건을 생성한다.
     *
     * 초기 상태는 PENDING으로 설정되며, transactionHash와 failureReason은 null이다.
     *
     * @param paymentId         외부 결제 식별자 (Kafka 이벤트에서 전달받은 고유 ID)
     * @param userId            결제 요청 사용자 ID
     * @param productName       상품명
     * @param productUrl        상품 URL
     * @param amount            결제 금액
     * @param currency          통화 코드 (예: KRW)
     * @param aiAgentPrivateKey AI 에이전트 개인키 (세션키 서명용, null이면 스텁 처리)
     * @param productImageUrl   상품 이미지 URL (null 가능)
     * @return 생성된 Payment 엔티티
     */
    // public 정적 팩토리 메서드로 외부에서 객체 만들 때 이걸 사용하도록 만듦
    public static Payment create(String paymentId, Long userId, Long subscriptionId, String productName, String productUrl,
                                 Integer amount, String currency, String aiAgentPrivateKey, String productImageUrl) {
        return new Payment(paymentId, userId, subscriptionId, productName, productUrl, amount, currency, aiAgentPrivateKey, productImageUrl);
    }

    /**
     * 블록체인 결제 성공 시 상태를 SUCCESS로 전이하고 트랜잭션 해시를 기록한다.
     *
     * @param transactionHash 블록체인에서 반환된 트랜잭션 해시
     */
    public void markSuccess(String transactionHash) {
        this.status = PaymentStatus.SUCCESS;
        this.transactionHash = transactionHash;
        this.failureReason = null;
    }

    /**
     * 블록체인 결제 성공 시 상태를 SUCCESS로 전이하고 트랜잭션 해시와 가스비를 기록한다.
     *
     * @param transactionHash 블록체인에서 반환된 트랜잭션 해시
     * @param gasFeeKrw       결제 가스비 (KRW 단위 정수, null이면 기록하지 않음)
     */
    public void markSuccess(String transactionHash, Integer gasFeeKrw) {
        this.status = PaymentStatus.SUCCESS;
        this.transactionHash = transactionHash;
        this.gasFeeKrw = gasFeeKrw;
        this.failureReason = null;
    }

    /**
     * 블록체인 결제 실패 시 상태를 FAILED로 전이하고 실패 사유를 기록한다.
     *
     * @param failureReason 결제 실패 원인 메시지
     */
    public void markFailed(String failureReason) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = failureReason;
        this.transactionHash = null;
    }

    /**
     * 엔티티 최초 저장 시 createdAt과 updatedAt을 현재 시각으로 설정한다.
     */
    @PrePersist     // DB에 INSERT 직전에 자동 실행
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 엔티티 갱신 시 updatedAt을 현재 시각으로 갱신한다.
     */
    @PreUpdate      // DB에 UPDATE 직전에 자동 실행
    void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
