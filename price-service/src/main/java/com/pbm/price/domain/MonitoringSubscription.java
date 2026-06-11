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
 *
 * 테이블명 이력:
 *   - 초기: monitoring_subscriptions
 *   - 현재: users_monitoring_subscriptions (사용자별 구독임을 명확히 함)
 */
@Getter
@Entity
@Table(
        name = "users_monitoring_subscriptions",
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

    /** 등록 시점의 상품 이미지 URL 스냅샷 */
    @Column(name = "snapshot_image_url", length = 1000)
    private String snapshotImageUrl;

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

    /**
     * 모니터링 방식 구분.
     * PLATFORM: 기존 방식 (Naver/AliExpress API로 가격 수집)
     * URL:      익스텐션이 직접 URL을 열어 가격을 수집하는 방식
     */
    @Column(name = "monitor_type", nullable = false, length = 20)
    private String monitorType;

    /**
     * URL 모니터링 조건 (monitor_type = URL 인 경우만 사용).
     * ALL: 등록된 URL 전부가 목표가에 도달해야 처리
     * ANY: 먼저 도달하는 URL 하나가 처리되면 나머지 취소
     */
    @Column(name = "url_condition", length = 10)
    private String urlCondition;

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

    /**
     * 모니터링 종료 예정 시각.
     * null이면 무기한 모니터링을 의미한다.
     * 스케줄러가 이 시각을 지난 ACTIVE 구독을 자동으로 COMPLETED 처리한다.
     */
    @Column(name = "scheduled_end_at")
    private Instant scheduledEndAt;

    // ──────────────────────────────────────────────────────────────────
    // Method B: 조건별 AI 에이전트 세션키 (AUTO_PURCHASE 전용)
    // ──────────────────────────────────────────────────────────────────

    /**
     * 이 조건 전용 AI 에이전트 이더리움 주소.
     * AUTO_PURCHASE intent의 신규 구독 생성 시 자동으로 생성되어 저장된다.
     * payment-service의 PBMSmartAccount.addSessionKey() 호출에 사용된다.
     */
    @Column(name = "ai_agent_address", length = 42)
    private String aiAgentAddress;

    /**
     * 이 조건 전용 AI 에이전트 개인키 (hex 문자열).
     * 목표 가격 달성 시 PaymentRequestEvent에 포함되어 payment-service가
     * PBMSmartAccount.executeAIPayment()를 서명하는 데 사용한다.
     * 보안 강화를 위해 프로덕션에서는 암호화하여 저장해야 한다.
     */
    @Column(name = "ai_agent_private_key", length = 128)
    private String aiAgentPrivateKey;

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
                                   String snapshotImageUrl,
                                   String searchKeyword,
                                   BigDecimal targetPrice,
                                   CurrencyType currency,
                                   String intent,
                                   MonitoringSubscriptionStatus status,
                                   Integer consecutiveMissCount,
                                   Integer checkIntervalMinutes,
                                   Instant scheduledEndAt,
                                   String monitorType,
                                   String urlCondition) {
        this.userId = userId;
        this.commandId = commandId;
        this.platform = platform;
        this.productId = productId;
        this.productUrl = productUrl;
        this.snapshotTitle = snapshotTitle;
        this.snapshotPrice = snapshotPrice;
        this.snapshotImageUrl = snapshotImageUrl;
        this.searchKeyword = searchKeyword;
        this.targetPrice = targetPrice;
        this.currency = currency;
        this.intent = intent;
        this.status = status;
        this.consecutiveMissCount = consecutiveMissCount;
        this.checkIntervalMinutes = checkIntervalMinutes;
        this.scheduledEndAt = scheduledEndAt;
        this.monitorType = monitorType != null ? monitorType : "PLATFORM";
        this.urlCondition = urlCondition;
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
     * @param snapshotImageUrl     등록 시점의 상품 이미지 URL
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
                                                String snapshotImageUrl,
                                                String searchKeyword,
                                                BigDecimal targetPrice,
                                                CurrencyType currency,
                                                String intent,
                                                MonitoringSubscriptionStatus status,
                                                Integer consecutiveMissCount,
                                                Integer checkIntervalMinutes,
                                                Instant scheduledEndAt) {
        return new MonitoringSubscription(
                userId, commandId, platform, productId, productUrl,
                snapshotTitle, snapshotPrice, snapshotImageUrl, searchKeyword, targetPrice,
                currency, intent, status, consecutiveMissCount, checkIntervalMinutes,
                scheduledEndAt, "PLATFORM", null
        );
    }

    /**
     * URL 기반 모니터링 구독을 생성한다.
     *
     * @param monitorType  "URL"
     * @param urlCondition "ALL" | "ANY"
     */
    public static MonitoringSubscription createUrl(Long userId,
                                                    String commandId,
                                                    String productUrl,
                                                    BigDecimal targetPrice,
                                                    CurrencyType currency,
                                                    String intent,
                                                    MonitoringSubscriptionStatus status,
                                                    Integer checkIntervalMinutes,
                                                    Instant scheduledEndAt,
                                                    String urlCondition) {
        // URL을 product_id로 직접 사용하면 VARCHAR(255) 초과 → MD5 해시(32자)로 저장
        String productId = hashUrl(productUrl);
        return new MonitoringSubscription(
                userId, commandId,
                Platform.URL,
                productId,              // productId = URL의 MD5 해시 (32자)
                productUrl,             // productUrl = 원본 URL 전체
                null,                   // snapshotTitle - 초기에는 없음
                null,                   // snapshotPrice
                null,                   // snapshotImageUrl
                null,                   // searchKeyword
                targetPrice,
                currency,
                intent, status, 0, checkIntervalMinutes,
                scheduledEndAt, "URL", urlCondition
        );
    }

    /**
     * URL을 MD5 해시로 변환하여 product_id용 32자 식별자를 생성한다.
     * VARCHAR(255) 컬럼 한도를 초과하는 긴 URL (쿼리파라미터 포함) 대응용.
     *
     * @param url 원본 URL
     * @return 32자 MD5 hex 문자열
     */
    public static String hashUrl(String url) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // MD5는 JVM 표준 보장 → 사실상 도달 불가, fallback으로 앞 32자만 사용
            return url.length() <= 32 ? url : url.substring(0, 32);
        }
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
     * 최신 commandId, 상품명, 가격, 이미지, 키워드, 목표 가격 등을 업데이트한다.
     *
     * @param commandId        새로운 명령 세션 ID
     * @param productUrl       최신 상품 URL
     * @param snapshotTitle    최신 상품명 스냅샷
     * @param snapshotPrice    최신 가격 스냅샷
     * @param snapshotImageUrl 최신 상품 이미지 URL
     * @param searchKeyword    최신 검색 키워드
     * @param targetPrice      새로운 목표 가격
     * @param intent           사용자 의도
     * @param currency         통화 구분
     */
    public void updateSelectionSnapshot(String commandId,
                                        String productUrl,
                                        String snapshotTitle,
                                        BigDecimal snapshotPrice,
                                        String snapshotImageUrl,
                                        String searchKeyword,
                                        BigDecimal targetPrice,
                                        String intent,
                                        CurrencyType currency) {
        this.commandId = commandId;
        this.productUrl = productUrl;
        this.snapshotTitle = snapshotTitle;
        this.snapshotPrice = snapshotPrice;
        this.snapshotImageUrl = snapshotImageUrl;
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
     * 알림/자동 결제 옵션(intent)을 변경한다.
     *
     * @param newIntent "AUTO_PURCHASE" 또는 "PRICE_TRACK"
     */
    public void updateIntent(String newIntent) {
        this.intent = newIntent;
    }

    /**
     * 목표 가격을 갱신한다.
     *
     * @param newTargetPrice 새로운 목표 가격
     */
    public void updateTargetPrice(BigDecimal newTargetPrice) {
        this.targetPrice = newTargetPrice;
    }

    /**
     * 모니터링 종료 예정 시각을 갱신한다.
     *
     * @param newScheduledEndAt 새로운 종료 예정 시각 (null이면 무기한)
     */
    public void updateScheduledEndAt(Instant newScheduledEndAt) {
        this.scheduledEndAt = newScheduledEndAt;
    }

    /**
     * URL 크롤링으로 수집한 상품명/이미지를 스냅샷에 반영한다.
     * <p>
     * 이미 값이 있으면 덮어쓰지 않는다 (최초 크롤링 결과만 보존).
     *
     * @param title    og:title 또는 h1에서 추출한 상품명
     * @param imageUrl og:image에서 추출한 대표 이미지 URL
     */
    public void updateSnapshotFromUrl(String title, String imageUrl) {
        if (this.snapshotTitle == null && title != null && !title.isBlank()) {
            this.snapshotTitle = title;
        }
        if (this.snapshotImageUrl == null && imageUrl != null && !imageUrl.isBlank()) {
            this.snapshotImageUrl = imageUrl;
        }
    }

    /**
     * URL 모니터링 최초 가격 보고 시 등록 시점 가격을 스냅샷에 저장한다.
     * 이미 값이 있으면 덮어쓰지 않는다 (등록 시점 기준가 보존).
     * 가격 이력은 price_history 테이블에서 시계열로 관리한다.
     *
     * @param currentPrice 익스텐션이 크롤링한 현재 판매가 (KRW 기준)
     */
    public void updateSnapshotPrice(BigDecimal currentPrice) {
        if (this.snapshotPrice != null) {
            return;
        }
        if (currentPrice != null && currentPrice.compareTo(BigDecimal.ZERO) > 0) {
            this.snapshotPrice = currentPrice;
        }
    }

    /**
     * 모니터링 종료 예정 시각이 지났는지 확인한다.
     *
     * @param now 현재 시각
     * @return 종료 예정 시각이 설정되어 있고 현재 시각보다 이전이면 true
     */
    public boolean isExpired(Instant now) {
        return scheduledEndAt != null && scheduledEndAt.isBefore(now);
    }

    /**
     * AI 에이전트 키페어를 이 구독에 할당한다.
     * <p>
     * AUTO_PURCHASE intent의 신규 구독 생성 시 호출되며,
     * payment-service가 세션키를 블록체인에 등록한 후 결제 시 사용한다.
     *
     * @param aiAgentAddress    AI 에이전트 이더리움 주소 (0x...)
     * @param aiAgentPrivateKey AI 에이전트 개인키 (64자리 hex)
     */
    public void assignSessionKey(String aiAgentAddress, String aiAgentPrivateKey) {
        this.aiAgentAddress = aiAgentAddress;
        this.aiAgentPrivateKey = aiAgentPrivateKey;
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
