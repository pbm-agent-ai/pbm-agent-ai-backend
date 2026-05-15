package com.pbm.price.domain;

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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 사용자별 모니터링 구독 엔티티.
 * <p>
 * 역할: 사용자가 등록한 개별 가격 모니터링 건을 저장한다.
 *       하나의 구독 건은 특정 플랫폼의 상품을 주기적으로 확인하며,
 *       목표 가격 도달 시 결제 또는 알림을 트리거하는 근거가 된다.
 * 동작: 사용자-플랫폼-상품 단위로 생성되며, 스케줄러가 next_check_at 기준으로
 *       수집 대상을 조회하여 가격을 갱신한다.
 * 연관: MonitoringSubscriptionRepository, MonitoringSubscriptionStatus.
 * @see MonitoringSubscriptionStatus
 */
@Getter
@Entity
@Table(
        name = "monitoring_subscriptions",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_ms_user_platform_product",
                        columnNames = {"user_id", "platform", "product_id"}
                )
        },
        indexes = {
                @Index(name = "idx_ms_user_id", columnList = "user_id"),
                @Index(name = "idx_ms_status_next_check", columnList = "status, next_check_at"),
                @Index(name = "idx_ms_command_id", columnList = "command_id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MonitoringSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 사용자 식별자 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 명령 세션 ID (UUID 문자열) */
    @Column(name = "command_id", nullable = false, length = 36)
    private String commandId;

    /** 플랫폼 구분 (NAVER, ALIEXPRESS 등) */
    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 30)
    private Platform platform;

    /** 플랫폼 내 상품 식별자 */
    @Column(name = "product_id", nullable = false, length = 255)
    private String productId;

    /** 상품 상세 페이지 URL (link 컬럼명 사용 금지) */
    @Column(name = "product_url", length = 1000)
    private String productUrl;

    /** 등록 시점의 상품명 스냅샷 */
    @Column(name = "snapshot_title", length = 500)
    private String snapshotTitle;

    /** 등록 시점의 상품 가격 스냅샷 */
    @Column(name = "snapshot_price", precision = 19, scale = 4)
    private BigDecimal snapshotPrice;

    /** 모니터링 등록에 사용된 검색 키워드 */
    @Column(name = "search_keyword", length = 255)
    private String searchKeyword;

    /** 사용자가 설정한 목표 가격 */
    @Column(name = "target_price", precision = 19, scale = 4)
    private BigDecimal targetPrice;

    /** 통화 구분 (KRW, USD) */
    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 10)
    private CurrencyType currency;

    /** 사용자 의도 (예: "AUTO_PURCHASE", "PRICE_TRACK") */
    @Column(name = "intent", length = 30)
    private String intent;

    /** 모니터링 상태 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MonitoringSubscriptionStatus status;

    /** 연속 수집 실패 횟수 */
    @Column(name = "consecutive_miss_count", nullable = false)
    private Integer consecutiveMissCount;

    /** 수집 주기 (분) */
    @Column(name = "check_interval_minutes", nullable = false)
    private Integer checkIntervalMinutes;

    /** 마지막 수집 시각 */
    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    /** 다음 수집 예정 시각 */
    @Column(name = "next_check_at")
    private Instant nextCheckAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private MonitoringSubscription(Long userId,
                                   String commandId,
                                   Platform platform,
                                   String productId,
                                   String productUrl,
                                   String snapshotTitle,
                                   BigDecimal snapshotPrice,
                                   String searchKeyword,
                                   BigDecimal targetPrice,
                                   CurrencyType currency,
                                   String intent,
                                   MonitoringSubscriptionStatus status,
                                   Integer consecutiveMissCount,
                                   Integer checkIntervalMinutes) {
        this.userId = userId;
        this.commandId = commandId;
        this.platform = platform;
        this.productId = productId;
        this.productUrl = productUrl;
        this.snapshotTitle = snapshotTitle;
        this.snapshotPrice = snapshotPrice;
        this.searchKeyword = searchKeyword;
        this.targetPrice = targetPrice;
        this.currency = currency;
        this.intent = intent;
        this.status = status;
        this.consecutiveMissCount = consecutiveMissCount;
        this.checkIntervalMinutes = checkIntervalMinutes;
    }

    /**
     * 새 모니터링 구독을 생성한다.
     *
     * @param userId               사용자 식별자
     * @param commandId            명령 세션 ID (UUID)
     * @param platform             플랫폼 구분
     * @param productId            플랫폼 내 상품 식별자
     * @param productUrl           상품 상세 페이지 URL
     * @param snapshotTitle        등록 시점의 상품명
     * @param snapshotPrice        등록 시점의 가격
     * @param searchKeyword        모니터링 등록에 사용된 검색 키워드
     * @param targetPrice          사용자 목표 가격
     * @param currency             통화 구분
     * @param intent               사용자 의도
     * @param status               초기 상태 (일반적으로 ACTIVE)
     * @param consecutiveMissCount 초기 연속 실패 횟수 (보통 0)
     * @param checkIntervalMinutes 수집 주기 (분)
     * @return 생성된 MonitoringSubscription 엔티티
     */
    public static MonitoringSubscription create(Long userId,
                                                String commandId,
                                                Platform platform,
                                                String productId,
                                                String productUrl,
                                                String snapshotTitle,
                                                BigDecimal snapshotPrice,
                                                String searchKeyword,
                                                BigDecimal targetPrice,
                                                CurrencyType currency,
                                                String intent,
                                                MonitoringSubscriptionStatus status,
                                                Integer consecutiveMissCount,
                                                Integer checkIntervalMinutes) {
        return new MonitoringSubscription(
                userId, commandId, platform, productId, productUrl,
                snapshotTitle, snapshotPrice, searchKeyword, targetPrice,
                currency, intent, status, consecutiveMissCount, checkIntervalMinutes
        );
    }

    /**
     * 수집 완료 시각과 다음 수집 예정 시각을 갱신한다.
     *
     * @param checkedAt 실제 수집 완료 시각
     */
    public void markChecked(Instant checkedAt) {
        this.lastCheckedAt = checkedAt;
        this.nextCheckAt = checkedAt.plusSeconds(checkIntervalMinutes.longValue() * 60L);
    }

    /**
     * 연속 실패 횟수를 초기화하고 수집 시각을 갱신한다.
     *
     * @param checkedAt 실제 수집 완료 시각
     */
    public void markSuccess(Instant checkedAt) {
        this.consecutiveMissCount = 0;
        markChecked(checkedAt);
    }

    /**
     * 연속 실패 횟수를 1 증가시키고 수집 시각을 갱신한다.
     *
     * @param checkedAt 실패 시각
     */
    public void markMiss(Instant checkedAt) {
        this.consecutiveMissCount++;
        markChecked(checkedAt);
    }

    /**
     * 기존 구독의 선택 정보 스냅샷을 갱신한다.
     * <p>
     * 사용자가 동일한 상품(사용자+플랫폼+productId 기준)을 다시 선택했을 때
     * 최신 commandId, 상품명, 가격, 키워드, 목표 가격 등을 업데이트한다.
     *
     * @param commandId     새로운 명령 세션 ID
     * @param productUrl    최신 상품 URL
     * @param snapshotTitle 최신 상품명 스냅샷
     * @param snapshotPrice 최신 가격 스냅샷
     * @param searchKeyword 최신 검색 키워드
     * @param targetPrice   새로운 목표 가격
     * @param intent        사용자 의도
     * @param currency      통화 구분
     */
    public void updateSelectionSnapshot(String commandId,
                                        String productUrl,
                                        String snapshotTitle,
                                        BigDecimal snapshotPrice,
                                        String searchKeyword,
                                        BigDecimal targetPrice,
                                        String intent,
                                        CurrencyType currency) {
        this.commandId = commandId;
        this.productUrl = productUrl;
        this.snapshotTitle = snapshotTitle;
        this.snapshotPrice = snapshotPrice;
        this.searchKeyword = searchKeyword;
        this.targetPrice = targetPrice;
        this.intent = intent;
        this.currency = currency;
    }

    /**
     * 연속 실패 횟수를 0으로 초기화한다.
     * 주로 갱신 또는 성공 처리 시 호출된다.
     */
    public void resetMissCount() {
        this.consecutiveMissCount = 0;
    }

    /**
     * 구독 상태를 변경한다.
     *
     * @param newStatus 변경할 상태
     */
    public void changeStatus(MonitoringSubscriptionStatus newStatus) {
        this.status = newStatus;
    }

    /**
     * 목표 가격을 갱신한다.
     *
     * @param newTargetPrice 새로운 목표 가격
     */
    public void updateTargetPrice(BigDecimal newTargetPrice) {
        this.targetPrice = newTargetPrice;
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
