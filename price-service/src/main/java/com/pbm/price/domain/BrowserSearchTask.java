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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 익스텐션 브라우저 검색 태스크 엔티티.
 *
 * 역할: AliExpress 초기 검색을 서버 대신 사용자 브라우저에서 수행하도록 큐잉한다.
 */
@Getter
@Entity
@Table(name = "browser_search_tasks")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BrowserSearchTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "command_id", nullable = false, length = 36, unique = true)
    private String commandId;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 30)
    private Platform platform;

    @Column(name = "keyword", nullable = false, length = 3000)
    private String keyword;

    @Column(name = "search_url", nullable = false, length = 1200)
    private String searchUrl;

    @Column(name = "target_price")
    private Integer targetPrice;

    @Column(name = "intent", length = 50)
    private String intent;

    @Column(name = "max_results", nullable = false)
    private Integer maxResults;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private BrowserSearchTaskStatus status;

    @Column(name = "last_dispatched_at")
    private Instant lastDispatchedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    /** 디스패치 횟수 — 재시도 제한용 (초과 시 자동 FAILED 처리) */
    @Column(name = "dispatch_count", nullable = false)
    private Integer dispatchCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private BrowserSearchTask(
            Long userId,
            String commandId,
            Platform platform,
            String keyword,
            String searchUrl,
            Integer targetPrice,
            String intent,
            Integer maxResults
    ) {
        this.userId = userId;
        this.commandId = commandId;
        this.platform = platform;
        this.keyword = keyword;
        this.searchUrl = searchUrl;
        this.targetPrice = targetPrice;
        this.intent = intent;
        this.maxResults = maxResults;
        this.status = BrowserSearchTaskStatus.PENDING;
        this.dispatchCount = 0;
    }

    public static BrowserSearchTask create(
            Long userId,
            String commandId,
            Platform platform,
            String keyword,
            String searchUrl,
            Integer targetPrice,
            String intent,
            Integer maxResults
    ) {
        return new BrowserSearchTask(userId, commandId, platform, keyword, searchUrl, targetPrice, intent, maxResults);
    }

    public void redispatch(Instant dispatchedAt) {
        this.status = BrowserSearchTaskStatus.DISPATCHED;
        this.lastDispatchedAt = dispatchedAt;
        this.failureReason = null;
        this.dispatchCount = (this.dispatchCount != null ? this.dispatchCount : 0) + 1;
    }

    public void complete(Instant completedAt) {
        this.status = BrowserSearchTaskStatus.COMPLETED;
        this.completedAt = completedAt;
    }

    public void fail(String reason) {
        this.status = BrowserSearchTaskStatus.FAILED;
        this.failureReason = reason;
    }

    public void resetForRetry() {
        this.status = BrowserSearchTaskStatus.PENDING;
        this.failureReason = null;
        this.completedAt = null;
        this.dispatchCount = 0;
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
