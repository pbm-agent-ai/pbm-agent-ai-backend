package com.pbm.price.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 공통 수집 대상 엔티티 (상품 단위).
 *
 * 역할: 여러 사용자가 같은 상품(플랫폼 + productId)을 모니터링하더라도
 *       하나의 수집 대상만 유지하기 위한 공유 기준 테이블이다.
 *       과거에는 키워드 단위(normalized_keyword)로 그루핑했으나
 *       productId 단위로 변경되어, 각 상품별로 공유 폴링 주기를 관리한다.
 * 동작: 모니터링 구독 생성/갱신 시 activate 되어 nextFetchAt이 설정되고,
 *       일반 검색 결과 저장 시에는 nextFetchAt = null 로 비활성 상태로 생성된다.
 *       PriceMonitoringScheduler가 nextFetchAt 도래한 대상만 수집한다.
 * 연관: Product (1:1 관계에 가깝게 동작).
 */
@Getter
@Entity
@Table(
        name = "monitor_targets",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_monitor_targets_platform_product",
                columnNames = {"platform", "product_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MonitorTarget {

    // JPA가 기본 키를 자동 생성한다.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 문자열 enum으로 저장하여 DB에서도 NAVER, ALIEXPRESS 값을 바로 읽을 수 있게 한다.
    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 30)
    private Platform platform;

    /**
     * 플랫폼 내 상품 식별자.
     * (NAVER는 productId, ALIEXPRESS는 product_id)
     */
    @Column(name = "product_id", nullable = false, length = 255)
    private String productId;

    /**
     * NAVER 재검색 또는 ALIEXPRESS 검색 fallback에 사용할 키워드.
     * 검색 결과 저장 시 각 product가 발견된 검색어로 설정된다.
     */
    @Column(name = "search_keyword", nullable = false, length = 255)
    private String searchKeyword;

    /**
     * 상품 상세 페이지 URL (선택).
     * fallback 매칭 시 사용된다.
     */
    @Column(name = "product_url", length = 1000)
    private String productUrl;

    @Column(name = "fetch_interval_minutes", nullable = false)
    private Integer fetchIntervalMinutes;

    @Column(name = "last_fetched_at")
    private Instant lastFetchedAt;

    @Column(name = "next_fetch_at")
    private Instant nextFetchAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private MonitorTarget(Platform platform, String productId, String searchKeyword, String productUrl, Integer fetchIntervalMinutes) {
        this.platform = platform;
        this.productId = productId;
        this.searchKeyword = searchKeyword;
        this.productUrl = productUrl;
        this.fetchIntervalMinutes = fetchIntervalMinutes;
    }

    /**
     * 새 공통 수집 대상을 생성한다.
     *
     * @param platform             플랫폼 구분(enum)
     * @param productId            플랫폼 내 상품 식별자
     * @param searchKeyword        NAVER 검색/ALIEXPRESS 검색 fallback에 사용할 키워드
     * @param productUrl           상품 상세 페이지 URL (선택, fallback 매칭용)
     * @param fetchIntervalMinutes 기본 수집 주기(분)
     * @return 생성된 MonitorTarget 엔티티
     */
    public static MonitorTarget create(Platform platform, String productId, String searchKeyword, String productUrl, Integer fetchIntervalMinutes) {
        return new MonitorTarget(platform, productId, searchKeyword, productUrl, fetchIntervalMinutes);
    }

    /**
     * 수집 완료 시각과 다음 수집 예정 시각을 갱신한다.
     * 모니터링 구독이 활성화될 때 또는 스케줄러가 실제 수집을 완료했을 때 호출된다.
     *
     * @param fetchedAt 실제 수집 완료 시각
     */
    public void markFetched(Instant fetchedAt) {
        this.lastFetchedAt = fetchedAt;
        this.nextFetchAt = fetchedAt.plusSeconds(fetchIntervalMinutes.longValue() * 60L);
    }

    /**
     * 검색 컨텍스트를 갱신한다.
     * 검색 결과 저장 시 또는 모니터링 구독 갱신 시 호출된다.
     *
     * @param searchKeyword 최신 검색 키워드
     * @param productUrl    최신 상품 URL (null이면 갱신하지 않음)
     */
    public void updateSearchContext(String searchKeyword, String productUrl) {
        this.searchKeyword = searchKeyword;
        if (productUrl != null && !productUrl.isBlank()) {
            this.productUrl = productUrl;
        }
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
