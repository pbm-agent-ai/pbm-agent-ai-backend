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
 * 공통 수집 대상 엔티티.
 *
 * 역할: 여러 사용자가 같은 키워드를 모니터링하더라도 플랫폼 + 정규화 키워드 기준으로
 *       하나의 수집 대상만 유지하기 위한 공유 기준 테이블이다.
 * 동작: search API 호출 또는 향후 모니터링 등록 시 생성/재사용되며, 마지막 수집 시각과
 *       다음 수집 예정 시각을 함께 관리한다.
 * 연관: Product.
 */
@Getter
@Entity
@Table(
        name = "monitor_targets",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_monitor_targets_platform_keyword",
                columnNames = {"platform", "normalized_keyword"}
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

    @Column(name = "normalized_keyword", nullable = false, length = 150)
    private String normalizedKeyword;

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

    private MonitorTarget(Platform platform, String normalizedKeyword, Integer fetchIntervalMinutes) {
        this.platform = platform;
        this.normalizedKeyword = normalizedKeyword;
        this.fetchIntervalMinutes = fetchIntervalMinutes;
    }

    /**
     * 새 공통 수집 대상을 생성한다.
     *
     * @param platform             플랫폼 구분(enum)
     * @param normalizedKeyword    공백/대소문자를 정리한 공유 키워드
     * @param fetchIntervalMinutes 기본 수집 주기(분)
     * @return 생성된 MonitorTarget 엔티티
     */
    public static MonitorTarget create(Platform platform, String normalizedKeyword, Integer fetchIntervalMinutes) {
        return new MonitorTarget(platform, normalizedKeyword, fetchIntervalMinutes);
    }

    /**
     * 수집 완료 시각과 다음 수집 예정 시각을 갱신한다.
     *
     * @param fetchedAt 실제 수집 완료 시각
     */
    public void markFetched(Instant fetchedAt) {
        this.lastFetchedAt = fetchedAt;
        this.nextFetchAt = fetchedAt.plusSeconds(fetchIntervalMinutes.longValue() * 60L);
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
