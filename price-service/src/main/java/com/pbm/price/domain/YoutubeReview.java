package com.pbm.price.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * YouTube 리뷰 영상 분석 결과 엔티티.
 * <p>
 * 역할: 유튜버 리뷰 영상의 자막을 AI로 분석한 결과를 저장한다.
 *       동일 영상(video_id)을 재분석하면 기존 데이터를 덮어쓴다.
 * 카테고리 구조:
 *   - category_main: 대분류 (예: HOME_APPLIANCE, ELECTRONICS)
 *   - category_sub:  소분류 (예: FOOD_PROCESSOR, EARPHONE)
 * 연관: YoutubeReviewProduct (1:N, CascadeType.ALL)
 */
@Getter
@Entity
@Table(
        name = "youtube_reviews",
        indexes = {
                @Index(name = "idx_yr_youtuber_name", columnList = "youtuber_name"),
                @Index(name = "idx_yr_category_main", columnList = "category_main"),
                @Index(name = "idx_yr_category_sub", columnList = "category_sub"),
                @Index(name = "idx_yr_created_at", columnList = "created_at")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class YoutubeReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** YouTube 영상 ID (11자리) */
    @Column(name = "video_id", nullable = false, unique = true, length = 20)
    private String videoId;

    /** 영상 전체 URL */
    @Column(name = "video_url", nullable = false, length = 500)
    private String videoUrl;

    /** 유튜버 이름 */
    @Column(name = "youtuber_name", nullable = false, length = 100)
    private String youtuberName;

    /** 자막 언어 코드 */
    @Column(name = "language", nullable = false, length = 10)
    private String language;

    /** 자동 생성 자막 여부 */
    @Column(name = "is_generated", nullable = false)
    private boolean isGenerated;

    /** 영상 전체 길이 (초) */
    @Column(name = "total_duration", nullable = false)
    private Double totalDuration;

    /**
     * 대분류 카테고리 (고정 목록).
     * 예: HOME_APPLIANCE, ELECTRONICS, LIVING, BEAUTY, FOOD
     */
    @Column(name = "category_main", nullable = false, length = 50)
    private String categoryMain;

    /**
     * 소분류 카테고리 (고정 목록).
     * 예: FOOD_PROCESSOR, VACUUM, EARPHONE, KEYBOARD, SMARTPHONE
     */
    @Column(name = "category_sub", nullable = false, length = 50)
    private String categorySub;

    /** 분석에 사용된 자막 구간 시작 시각 (초). 전체 분석 시 null */
    @Column(name = "analyzed_text_start_time")
    private Double analyzedTextStartTime;

    /** 분석에 사용된 자막 원문 */
    @Column(name = "raw_conclusion_text", columnDefinition = "TEXT")
    private String rawConclusionText;

    /** 분석된 상품 목록 (순위순) */
    @OneToMany(mappedBy = "review", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("rank ASC NULLS LAST")
    private List<YoutubeReviewProduct> products = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private YoutubeReview(String videoId, String videoUrl, String youtuberName,
                          String language, boolean isGenerated, Double totalDuration,
                          String categoryMain, String categorySub,
                          Double analyzedTextStartTime, String rawConclusionText) {
        this.videoId = videoId;
        this.videoUrl = videoUrl;
        this.youtuberName = youtuberName;
        this.language = language;
        this.isGenerated = isGenerated;
        this.totalDuration = totalDuration;
        this.categoryMain = categoryMain;
        this.categorySub = categorySub;
        this.analyzedTextStartTime = analyzedTextStartTime;
        this.rawConclusionText = rawConclusionText;
    }

    public static YoutubeReview create(String videoId, String videoUrl, String youtuberName,
                                       String language, boolean isGenerated, Double totalDuration,
                                       String categoryMain, String categorySub,
                                       Double analyzedTextStartTime, String rawConclusionText) {
        return new YoutubeReview(videoId, videoUrl, youtuberName, language, isGenerated,
                totalDuration, categoryMain, categorySub, analyzedTextStartTime, rawConclusionText);
    }

    /**
     * 재분석 시 기존 데이터를 최신 분석 결과로 갱신한다.
     */
    public void update(String youtuberName, String language, boolean isGenerated,
                       Double totalDuration, String categoryMain, String categorySub,
                       Double analyzedTextStartTime, String rawConclusionText) {
        this.youtuberName = youtuberName;
        this.language = language;
        this.isGenerated = isGenerated;
        this.totalDuration = totalDuration;
        this.categoryMain = categoryMain;
        this.categorySub = categorySub;
        this.analyzedTextStartTime = analyzedTextStartTime;
        this.rawConclusionText = rawConclusionText;
        this.products.clear();
    }

    /** 상품 목록에 분석된 상품을 추가한다. */
    public void addProduct(YoutubeReviewProduct product) {
        this.products.add(product);
        product.assignReview(this);
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
